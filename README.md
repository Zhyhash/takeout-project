# Tokeout

Tokeout 是一个基于 Spring Boot 的外卖后端练习项目。本文档记录当前项目已经处理的并发竞态、订单创建幂等的最终保障，以及仍处于实验或待处理状态的边界。

> 本文以当前实体/业务代码、`MysqlApiIntegrationTest.recreateSchema()` 中的九表 MySQL DDL 和现有集成测试为准。Redis 订单幂等目前是实验入口，生产 Controller 仍直接调用数据库方案。

## 技术与结构

- Java 17、Spring Boot 4.0.6、Maven Wrapper；
- Spring MVC、Spring Security、JWT、Jakarta Validation；
- MyBatis-Plus、PageHelper、MySQL 8；
- Spring Data Redis，用于订单创建幂等实验；
- H2 用于 API 隔离测试，MySQL 用于事务、唯一键和并发集成测试。

业务代码按 `Cart`、`Category`、`Merchant`、`Order`、`Product`、`User`、`Rider`、`DeliveryTask` 分域。订单创建进一步拆为：

```text
OrderController
  -> OrderService                 请求编排、幂等结果查询、数据准备
      -> OrderTransactionExecutor 订单/购物车/库存/明细的事务边界
          -> OrderItemService     条件扣减库存并生成订单明细
      -> OrderVOBuilder           返回对象组装
```

履约链路由订单与配送任务共同维护：

```text
用户支付 -> 商家接单 -> 商家出餐并创建配送任务
        -> 正常骑手抢单 -> 骑手确认送达 -> 用户确认收货
```

## 已解决的竞态

| 场景 | 原风险 | 当前控制 | 当前结果 |
|---|---|---|---|
| 相同用户、相同 `requestId` 并发下单 | 重复订单、重复扣库存、调用方无法确认首单结果 | `orders(user_id, request_id)` 唯一键；重复键后查询已提交订单；订单副作用在同一事务中 | 两个请求返回同一订单，数据库只保留一张订单和一组明细 |
| 不同 `requestId` 并发消费同一购物车 | 同一购物车形成多张订单 | 事务内按已读取 ID 批量删除购物车，并校验删除行数 | 只有一个请求完成；另一个因购物车删除行数不符而回滚 |
| 库存并发扣减 | 超卖或丢失更新 | `UPDATE product SET stock = stock - n WHERE id = ? AND stock >= n` | 库存不足时更新 0 行并回滚整个下单事务 |
| 重复/并发添加同一商品到购物车 | 重复行或数量丢失 | `uk_user_product(user_id, product_id)` + `INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + 1` | 只保留一条购物车记录，数量按每次 `+1` 指令累计 |
| 购物车数量更新/减到 0 | 旧读覆盖新值，或旧版本误删 | `cart.version` 乐观锁；删除时显式匹配 `id + version` | 过期写入或删除影响 0 行并返回业务错误 |
| 同一订单并发取消 | 重复归还库存 | 条件状态更新 `WAIT_PAY -> CANCELLED`；状态更新和库存归还同一事务 | 一次成功、一次失败，库存只归还一次 |
| 同一订单并发支付/确认 | 重复推进状态或重复写时间 | SQL 在 `WHERE` 中匹配用户和旧状态 | 每个合法状态转换只有一次成功 |
| 同一商家并发创建同名分类 | “先查后插”同时通过 | `uk_merchant_category(merchant_id, category_name)` | 数据库最终拒绝重复分类 |
| 创建商品与删除其分类并发 | 产生指向已删除分类的孤儿商品 | 两个操作都对目标分类行 `SELECT ... FOR UPDATE`；另有 `product.category_id` 外键 | 操作串行化，最终不产生孤儿商品 |
| 商家或商品实体并发更新 | 后写覆盖先写 | `merchant.version`、`product.version` 与 MyBatis-Plus 乐观锁 | Mapper 层过期版本更新影响 0 行；商家和商品乐观锁集成测试通过 |
| 同一订单重复出餐 | 创建多条配送任务，或订单与任务状态不一致 | `delivery_task.order_id` 唯一键；订单状态条件更新；同一事务创建任务 | 一张订单最多一条待接任务；重复出餐只在订单和任务均处于预期状态时幂等返回 |
| 多名骑手并发抢单 | 一单多骑手、任务和订单状态分裂 | `WAIT_ASSIGN + rider_id IS NULL` 条件更新；随后在同一事务推进订单 `READY -> DELIVERING` | 只有一名骑手成功，失败分支回滚，当前骑手重试幂等 |
| 骑手重复确认送达 | 重复推进或任务/订单不一致 | 任务与订单在同一事务执行条件状态更新 | 任务进入 `COMPLETED`、订单进入 `DELIVERED`；当前骑手重复请求幂等 |
| 商品上下架 | 旧版本覆盖或目标状态写错 | 统一合法性检查与 MyBatis-Plus 乐观锁更新 | 上架、下架及同状态重试均由 API/MySQL 集成测试验证 |

## 订单创建的最终方案

订单创建把客户端的一次业务意图表示为 `requestId`。该字段不能为空、最长 64 个字符，并且只在当前用户范围内唯一；不同用户可以使用相同的 `requestId`。

实际生效的链路如下：

1. `OrderService` 先按 `(userId, requestId)` 查询；已存在时直接返回该订单的当前数据。
2. 未命中时读取当前可下单购物车、商家和商品，计算订单金额。
3. `OrderTransactionExecutor` 开启数据库事务，并先插入带 `requestId` 的订单。
4. `uk_orders_user_request_id(user_id, request_id)` 是最终并发裁决点。相同键的第二个事务会等待第一个事务结束：首事务提交时第二次插入触发重复键并返回已提交订单；首事务回滚时第二次插入可以继续成功。
5. 新订单事务继续删除本次读取的购物车行、按库存下限条件扣减库存、写入订单明细。任一步失败，订单、购物车、库存和明细一起回滚。

最终原则是：数据库唯一键负责识别同一次下单意图，数据库事务负责副作用原子性，条件更新负责库存和状态机并发安全。正确性不依赖进程内锁，也不依赖 Redis 可用性。

## Redis 实验层的定位

`RedisOrderCreationExperiment` 已验证以下状态模型：

```text
不存在 --SET NX, 10 s--> PROCESSING --订单成功, 10 min--> SUCCEEDED:{orderId}
                              |
                              +--订单失败或回写失败--> 清除 key，允许重试
```

Redis key 为 `idempotency:order:create:{userId}:{requestId}`。`SUCCEEDED` 可直接定位并返回订单，`PROCESSING` 会快速返回“订单正在创建中”；请求入口读取 Redis 失败时会退回数据库幂等方案。

这部分目前是实验能力，而不是对外最终链路：

- `OrderController` 注入并调用的仍是 `OrderService`；
- 类名保留 `Experiment`，Redis 状态机由专门集成测试直接调用；
- Redis 只适合作为削峰和快速重放层，数据库唯一键仍必须保留；
- 当前只在入口 `get` 抛出 `DataAccessException` 时明确降级，接入 Controller 前仍需统一其他 Redis 操作失败时的降级语义。

## 骑手与配送任务接口

骑手注册、登录为公开接口；其余 `/rider/**` 路径要求 JWT 中的 `rider` 角色。每次配送查询或状态变更还会查询 `rider` 表，拒绝不存在或已禁用的骑手。

```text
POST  /rider/register
POST  /rider/login
GET   /rider/delivery-tasks/available?pageNum=1&pageSize=10
GET   /rider/delivery-tasks/current
GET   /rider/delivery-tasks/{taskId}
PATCH /rider/delivery-tasks/{taskId}/claim
PATCH /rider/delivery-tasks/{taskId}/complete
```

配送任务保存商家名称、配送奖励、收货人与商家的电话/地址快照。任务详情只能由已接取该任务的骑手读取；可接任务与当前任务列表分别按任务状态和骑手归属过滤。

## 尚未消除或刻意接受的边界

- 并发向空购物车加入不同商家的商品时，当前“先查冲突再插入”仍可能让两个商家的记录同时进入购物车；查询和下单会识别多商家并禁止购买，但数据库没有从结构上禁止它。
- 购物车的 `+1/-1` 被定义为独立数量指令，不使用请求幂等键；客户端重复发送会重复累计，这是当前业务语义。
- `PATCH /order/{id}/pay` 仍是本地模拟支付。接入真实支付、退款、优惠券或消息投递前，必须为外部副作用设计独立幂等键、回调去重和对账机制。
- Redis `PROCESSING` 过期可能允许长事务期间的再次尝试；数据库唯一键保证不会形成第二张同请求订单，但接入时仍应评估续期或更合理的超时。
- 数据库 DDL 仍只存在于集成测试，没有 Flyway/Liquibase 迁移；部署环境必须自行保证九张表、唯一键、版本列和配送快照字段一致。
- 配送奖励当前由 `DeliveryFeeCalculator` 固定返回 `5`，尚未实现距离、区域、时段和结算规则。
- 可接任务和当前任务列表尚未显式排序；数据量增长前应增加与过滤、排序匹配的联合索引。

更完整的接口幂等判断见 [IdempotencyDesign.md](IdempotencyDesign.md)，实际表结构见 [数据库结构.md](数据库结构.md)，剩余结构问题见 [数据库设计检查报告.md](数据库设计检查报告.md)。

## 运行与测试

完整测试需要：

- MySQL 8：`localhost:3306/takeout_integration_test`，测试配置使用 `root/root`，并允许测试创建该库；
- Redis：`127.0.0.1:6379`，数据库 0；
- Java 17。

Windows：

```powershell
.\mvnw.cmd test
```

如果 Maven Wrapper 3.3.4 在当前 Windows PowerShell 环境因目录 `Target[0]` 报错，可使用已安装的 Maven 3.9.15：

```powershell
mvn test
```

跳过两个 Redis 专用测试、运行其余全部测试：

```powershell
mvn test "-Dtest=!RedisBasicIntegrationTest,!RedisServiceIntegrationTest"
```

Linux/macOS：

```bash
./mvnw test
```

注意：`MysqlApiIntegrationTest` 会在 `takeout_integration_test` 中反复删除并重建九张业务表；请只对专用测试数据库执行完整测试。Redis 测试也会写入并清理带测试前缀或随机标识的 key。
