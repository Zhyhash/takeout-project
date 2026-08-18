# Tokeout

Tokeout 是一个基于 Spring Boot 的外卖后端练习项目。项目围绕用户、商家、骑手三类角色，实现了店铺与商品浏览、购物车、订单、商家履约和骑手配送的最小业务闭环。

> 本仓库当前只包含后端服务，不包含 Web、App 或小程序前端。以下说明以当前工作区中的 Controller、Service、配置文件、`deploy/schema.sql` 和测试代码为准。

## 1. 项目介绍

项目包含三类业务角色：

- 用户：注册登录、浏览店铺与商品、维护购物车、创建/支付/取消订单、查询订单和确认收货；
- 商家：注册登录、维护店铺状态、管理分类与商品、查看订单、接单和确认出餐；
- 骑手：注册登录、查看可接配送任务、抢单、查看当前任务和确认送达。

当前核心闭环为：

```text
用户浏览店铺并加入购物车
  -> 创建订单
  -> 模拟支付
  -> 商家接单
  -> 商家确认出餐并创建配送任务
  -> 骑手抢单
  -> 骑手确认送达
  -> 用户确认收货
```

系统使用 JWT 区分 `user`、`merchant`、`rider` 三类身份，统一通过 `Result` 返回接口结果，并由全局异常处理器转换参数、鉴权和业务异常。

## 2. 技术栈

| 分类 | 技术 |
|---|---|
| 运行环境 | Java 17、Maven Wrapper |
| 基础框架 | Spring Boot 4.0.6、Spring MVC、Spring Scheduling |
| 鉴权与校验 | Spring Security、JWT（JJWT 0.11.5）、BCrypt、Jakarta Validation |
| 数据访问 | MyBatis-Plus 3.5.7、PageHelper 2.1.1、MySQL 8 |
| 缓存/实验 | Spring Data Redis、Redis 7 |
| 对象转换 | MapStruct 1.5.5、Lombok 1.18.30 |
| 接口文档 | Knife4j 4.5.0、OpenAPI 3 |
| 测试 | JUnit 5、Spring Boot Test、MockMvc、H2、MySQL/Redis 集成测试 |
| 部署 | Docker、Docker Compose |

Spring Security 的过滤链目前配置为无状态并放行请求，实际 JWT 解析、公开路径和角色限制由 `JwtInterceptor` 与 `AuthPathMatcher` 完成。

## 3. 模块结构

```text
.
├─ deploy/
│  ├─ schema.sql                    MySQL 8 空库初始化脚本
│  └─ demo-data.sql                 可重复执行的本地演示数据
├─ src/main/java/org/example/takeout/
│  ├─ User/                         用户注册、登录、账号状态校验
│  ├─ Merchant/                     商家账号、店铺查询、商家订单履约
│  ├─ Category/                     商家商品分类
│  ├─ Product/                      商品、上下架、库存与商品缓存代码
│  ├─ Cart/                         购物车增删改查与可下单校验
│  ├─ Order/                        下单、支付、取消、查询、状态流转与超时任务
│  ├─ Rider/                        骑手账号
│  ├─ DeliveryTask/                 配送任务创建、抢单、送达与查询
│  ├─ Common/                       鉴权、上下文、异常、统一响应、Redis 实验和工具类
│  ├─ Config/                       拦截器、Security、JWT、MyBatis-Plus 配置
│  └─ TokeoutApplication.java       应用入口，启用定时任务
├─ src/main/resources/
│  ├─ application.yaml              主配置
│  ├─ mapper/ProductMapper.xml      商品恢复 SQL
│  └─ static/images/                默认商品图片
├─ src/test/                        API、单元和集成测试
├─ Dockerfile
├─ compose.yaml
└─ pom.xml
```

各业务模块大体采用 `Controller -> Service -> Mapper -> MySQL` 的调用结构，并按需要包含 `DTO`、`Entity`、`VO`、`Domain`、`Enums` 和转换器。

| 模块 | 当前职责 |
|---|---|
| `User` | 用户注册、登录、旧 Token 对应账号状态复核 |
| `Merchant` | 商家注册登录、店铺资料/营业状态、店铺查询、订单查询与接单出餐 |
| `Category` | 分类创建、查询、删除；删除普通分类时将商品迁移到默认分类 |
| `Product` | 商品创建、列表、上下架、恢复、库存条件扣减及缓存相关服务 |
| `Cart` | 单商家购物车校验、商品增减、列表、批量删除与清空 |
| `Order` | 订单创建幂等、事务下单、支付/取消/确认、订单查询、超时取消 |
| `Rider` | 骑手注册、登录和状态校验 |
| `DeliveryTask` | 出餐后建任务、可接任务、抢单、当前任务、详情与完成配送 |
| `Common` | JWT 鉴权、ThreadLocal 身份上下文、异常、响应模型和 Redis 幂等实验 |

## 4. 核心业务流程

### 4.1 注册、登录与鉴权

用户、商家、骑手分别通过自己的注册和登录接口进入系统。密码使用 BCrypt 保存；登录成功后签发带有主体 ID 和角色的 JWT。除公开路径外，请求需携带：

```http
Authorization: Bearer <token>
```

公开路径主要包括三类角色的注册/登录、顾客端店铺浏览，以及 Knife4j/OpenAPI 文档。购物车和订单只允许用户访问；分类和商家履约接口只允许商家访问；配送任务只允许骑手访问。

### 4.2 店铺、商品与购物车

1. 商家注册时会在同一事务内创建“默认分类”。
2. 商家创建分类和商品，并对商品执行上架/下架操作。
3. 用户通过 `/api/customer/shops` 浏览店铺及已上架商品。
4. 用户将商品加入购物车。业务上限制一个购物车只包含同一家店铺的商品。
5. 购物车列表会复核商品状态和商家营业状态，并给出是否可下单及失效原因。

### 4.3 创建订单

`POST /order` 使用客户端提供的 `requestId` 表示一次下单意图，实际生效的链路如下：

1. 按 `(userId, requestId)` 查询已有订单；命中时直接返回原订单。
2. 读取购物车并确认所有商品均可售、商家可下单且购物车只有一个商家。
3. 使用商品当前价格计算总金额。
4. 在事务内插入订单、删除本次消费的购物车记录、按条件扣减库存、写入订单明细。
5. `orders(user_id, request_id)` 唯一键处理相同请求的并发竞争；事务确保订单、购物车、库存和明细一起提交或回滚。

库存扣减 SQL 同时校验商品未删除、处于上架状态且库存充足，从数据库层避免超卖。待支付订单创建超过 30 分钟后，定时任务会尝试取消订单并归还库存；默认每 60 秒扫描一次，每批最多处理 100 个订单。

### 4.4 订单与配送状态流转

```text
WAIT_PAY
  ├─ 用户取消 / 30 分钟超时 -> CANCELLED（归还库存）
  └─ 模拟支付 -> PAYING -> PAID
       -> 商家接单 -> PREPARING
       -> 商家确认出餐 -> READY + 创建 WAIT_ASSIGN 配送任务
       -> 骑手抢单 -> DELIVERING
       -> 骑手确认送达 -> DELIVERED + 配送任务 COMPLETED
       -> 用户确认收货 -> FINISHED
```

商家出餐、骑手抢单和骑手送达会在各自事务内同步推进订单与配送任务。配送任务保存商家、收货人、电话、地址和配送奖励快照，避免履约过程中基础资料变化影响已有任务。

对外 VO 中的业务状态统一同时返回数字 `status` 和中文 `statusDesc`，例如 `{"status":0,"statusDesc":"待支付"}`。数字状态用于程序判断和接口筛选，状态文案用于页面展示；无法识别的状态码返回“未知状态”并记录告警。

## 5. 环境配置

### 5.1 必需环境

- JDK 17；
- MySQL 8；
- Maven 3.9+ 或仓库内 Maven Wrapper；
- Redis 7（可选，具体见“Redis 是否必须”）；
- Docker Desktop 或 Docker Engine + Compose 插件（仅 Docker 启动方式需要）。

### 5.2 必需配置

`src/main/resources/application.yaml` 没有数据库账号或 JWT 密钥的默认值，启动前必须提供：

| 环境变量 | 示例 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://127.0.0.1:3306/takeout?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true` | JDBC 地址，时区使用北京时间 |
| `DB_USERNAME` | `root` | 数据库用户名 |
| `DB_PASSWORD` | `your-password` | 数据库密码 |
| `JWT_SECRET` | 随机字符串 | HS256 密钥，UTF-8 长度不得少于 32 字节 |

未提供 `JWT_SECRET` 或密钥过短时，应用会拒绝启动。不要在共享或生产环境复用 README、测试配置或 `compose.yaml` 中的示例凭据。

### 5.3 可选配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis 地址 |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis 端口 |
| `SPRING_DATA_REDIS_DATABASE` | `0` | Redis 数据库 |
| `ORDER_TIMEOUT_ENABLED` | `true` | 是否启用待支付订单超时取消 |
| `ORDER_TIMEOUT_SCAN_INTERVAL_MS` | `60000` | 扫描间隔 |
| `ORDER_TIMEOUT_INITIAL_DELAY_MS` | `60000` | 首次扫描延迟 |
| `SERVER_PORT` | `8080` | HTTP 端口 |

## 6. 启动方式

启动前先按下一节初始化数据库。

### 6.1 Windows PowerShell

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/takeout?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = 'your-password'
$env:JWT_SECRET = 'replace-with-at-least-32-byte-secret'

.\mvnw.cmd spring-boot:run
```

如果当前 PowerShell 环境无法执行 Wrapper，可使用本机 Maven：

```powershell
mvn spring-boot:run
```

### 6.2 Linux/macOS

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/takeout?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USERNAME='root'
export DB_PASSWORD='your-password'
export JWT_SECRET='replace-with-at-least-32-byte-secret'

./mvnw spring-boot:run
```

### 6.3 运行打包后的 JAR

```powershell
mvn -DskipTests clean package
java -jar target/tokeout-0.0.1-SNAPSHOT.jar
```

### 6.4 Docker Compose

先启动 Docker Desktop（或确认 Docker Engine 正在运行），并用 `docker version`、`docker compose version` 确认客户端和引擎可用。安装后如果当前终端仍提示找不到 `docker`，请重新打开终端或把 Docker CLI 安装目录加入 `PATH`。

Dockerfile 会复制已经打包好的 JAR，因此先在项目根目录生成 JAR，再让 Compose 构建镜像并在后台启动应用、MySQL 和 Redis：

```powershell
mvn -DskipTests clean package
docker compose up -d --build
```

Compose 的健康检查会等待 MySQL 和 Redis 就绪后再启动应用。首次创建数据库卷时，会按文件名顺序自动执行 `01-schema.sql` 和 `02-demo-data.sql`，即先建表、再导入演示数据。应用 JVM、MySQL 和 Redis 容器统一使用 `Asia/Shanghai`，MySQL 会话默认时区为 `+08:00`。

默认宿主机端口如下：

| 服务 | 宿主机端口 | 容器端口 |
|---|---:|---:|
| 应用 | `8080` | `8080` |
| MySQL | `3307` | `3306` |
| Redis | `6379` | `6379` |

MySQL 默认使用 `3307`，以减少与本机已有 MySQL 的端口冲突。可以在启动前通过环境变量覆盖端口，例如 PowerShell：

```powershell
$env:APP_PORT = '18080'
$env:MYSQL_PORT = '13306'
$env:REDIS_PORT = '16379'
docker compose up -d --build
```

启动后可检查容器、日志和公开接口：

```powershell
docker compose ps
docker compose logs -f app
Invoke-RestMethod 'http://localhost:8080/api/customer/shops?pageNum=1&size=10'
```

如果修改了 Java 代码，需要重新打包并重建应用容器：

```powershell
mvn -DskipTests clean package
docker compose up -d --build app
```

停止、再次启动和删除容器：

```powershell
docker compose stop
docker compose start
docker compose down
```

`docker compose down` 会保留名为 `mysql-data` 的数据库卷。只有确认不再需要现有 Docker 数据时，才使用 `docker compose down -v` 删除数据卷；下次启动时会重新执行初始化和演示数据脚本。`compose.yaml` 中的数据库账号、密码和 JWT 仅适合本地演示，不得直接用于共享或生产环境。

### 6.5 运行测试

```powershell
mvn test
```

默认测试配置使用 H2，但多组集成测试会直接连接 `localhost:3306/takeout_integration_test`，并使用 `root/root`。其中 `MysqlApiIntegrationTest` 会反复删除并重建九张业务表，只能对专用测试库运行。Redis 不可用时，Redis 专用测试通过 JUnit assumption 跳过；要覆盖 Redis 行为则需提供 `127.0.0.1:6379`。如需让测试连接 Compose 中的 MySQL，应先确认宿主机 `3306` 未被占用，再设置 `$env:MYSQL_PORT = '3306'` 后启动依赖容器。

## 7. 数据库初始化

仓库提供 [`deploy/schema.sql`](deploy/schema.sql)，用于创建 `takeout` 数据库及以下九张表：

```text
user
merchant
category
product
cart
orders
order_item
rider
delivery_task
```

在项目根目录执行：

```powershell
mysql -u root -p --execute="source deploy/schema.sql"
```

### 7.1 导入演示数据

完成建表后，可继续执行 [`deploy/demo-data.sql`](deploy/demo-data.sql)：

```powershell
mysql -u root -p --execute="source deploy/demo-data.sql"
```

使用 `compose.yaml` 启动全新的 MySQL 容器时无需手工执行以上命令：Compose 已将 `schema.sql` 和 `demo-data.sql` 分别挂载为 `01-schema.sql`、`02-demo-data.sql`。MySQL 官方镜像只会在数据目录首次初始化时执行 `/docker-entrypoint-initdb.d/` 中的脚本；对已经初始化的容器执行 `docker compose restart` 或再次 `up` 不会重复导入。如需恢复演示数据，可手工执行 `demo-data.sql`，或在确认不需要现有容器数据后重新创建 MySQL 容器。

演示账号统一使用密码 `Demo@123456`：

| 角色 | 登录名 | 手机号 |
|---|---|---|
| 用户 | `demo_user` | `13900000001` |
| 商家 | `demo_merchant` | `13900000002` |
| 骑手 | `demo_rider` | `13900000003` |

脚本会准备以下调试场景：

- 演示商家、默认分类、热销分类和三个上架商品；
- 演示用户购物车中预置两瓶“演示可乐”，登录后可直接创建新订单；
- 一张 `PAID` 订单，用于测试商家接单；
- 一张 `READY` 订单和 `WAIT_ASSIGN` 配送任务，用于测试骑手抢单；
- 一张已由演示骑手接取的 `DELIVERING` 订单，用于测试骑手确认送达；
- 一张 `DELIVERED` 订单，用于测试用户确认收货。

订单和配送任务 ID 由数据库生成，脚本执行结束时会输出演示订单、配送任务和购物车记录的实际 ID；也可以在调试动作前通过对应的列表接口查询。脚本使用演示账号、订单号和请求号作为稳定业务键，可重复执行；再次执行会把演示购物车、订单明细和配送状态恢复为上述初始状态，不会清空普通业务数据。

> `demo-data.sql` 包含固定账号和密码，只能用于本地开发或临时测试环境，禁止用于生产或包含真实数据的共享环境。

### 7.2 初始化脚本边界

`schema.sql` 只负责空库基线：

- 使用 `CREATE DATABASE IF NOT EXISTS` 和 `CREATE TABLE IF NOT EXISTS`，不会删除现有业务数据；
- 不会升级已经存在但结构较旧的表；
- `schema.sql` 本身不包含演示数据；Compose 通过单独挂载的 `demo-data.sql` 在首次初始化时导入；
- 应用 JAR 不会自动执行该脚本；
- 项目尚未接入 Flyway 或 Liquibase。

商家通过注册接口创建后会自动获得默认分类，不需要手工插入默认分类数据。

## 8. Redis 是否必须

结论：**运行当前对外主业务接口时，Redis 不是必须依赖；MySQL 才是最终一致性和并发正确性的基础。**

当前 Redis 代码有两类用途：

1. `ProductService` 中实现了商品详情的 Cache-Aside 读写与修改、逻辑删除后的缓存清理。读取、写入和大部分删除失败会降级到 MySQL；商家端通过 `GET/PUT/DELETE /category/products/{id}` 使用对应能力。
2. `RedisOrderCreationExperiment` 实现了 `PROCESSING -> SUCCEEDED:{orderId}` 的下单幂等实验，但 `OrderController` 仍直接调用 `OrderService`，生产请求链路未接入该实验。

因此：

- 应用通常可以在 Redis 未启动时启动；
- 当前下单幂等由 MySQL 唯一键 `uk_orders_user_request_id` 和事务保证；
- 当前已暴露的主业务流程不因 Redis 缺失而失去正确性；
- 运行 Redis 集成测试或继续接入商品缓存/Redis 幂等时，需要 Redis；
- Docker Compose 为了提供完整实验环境仍会同时启动 Redis。

## 9. 接口文档入口

当前项目**没有手工编写 Knife4j/OpenAPI 配置类，也没有使用 `@Operation`、`@Schema` 等接口文档注解**。README 中提到它，是因为代码中仍存在两处明确痕迹：

- `pom.xml` 声明了 `knife4j-openapi3-jakarta-spring-boot-starter:4.5.0`；
- `AuthPathMatcher` 将 `/doc.html`、`/v3/api-docs`、`/swagger` 和 `/webjars/` 加入了公开路径。

因此，下面是 Knife4j Starter 自动配置约定的**候选入口**，不是项目已经单独配置并完成运行验证的文档模块：

- Knife4j UI：<http://localhost:8080/doc.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

如果启动后入口不可用，应先检查 Knife4j 4.5.0 与当前 Spring Boot 4.0.6 的兼容性。若项目确认不准备使用接口文档，应同时删除 `pom.xml` 中的 Knife4j 依赖和 `AuthPathMatcher` 中对应的公开路径，而不是继续把这些地址当作正式能力。

当前主要接口前缀如下：

| 前缀 | 角色/用途 |
|---|---|
| `/user` | 用户注册、登录 |
| `/api/customer/shops` | 公开的店铺列表与店铺详情 |
| `/cart/items` | 用户购物车 |
| `/order` | 用户订单 |
| `/merchant` | 商家注册登录、资料、订单履约和商品恢复 |
| `/category`、`/category/products` | 商家分类与商品管理 |
| `/rider` | 骑手注册登录 |
| `/rider/delivery-tasks` | 骑手配送任务 |

受保护接口需要在请求头中提供 `Authorization: Bearer <token>`。项目没有统一的接口前缀版本号（如 `/api/v1`）；在接口文档能力未实际验证前，接口定义以当前 Controller 为准。

## 10. 项目亮点

- **数据库级订单幂等**：`requestId` 与用户 ID 组成唯一键，相同业务请求并发执行时最终只生成一张订单。
- **事务化下单**：订单、购物车消费、条件扣库存和订单明细处于同一事务，任一失败则整体回滚。
- **并发控制**：库存使用带库存下限和商品状态的条件更新；购物车、商家、商品使用唯一约束、版本列或条件更新避免常见竞态。
- **完整履约状态机**：订单状态与配送任务状态分离，并在出餐、抢单、送达等关键动作中保持事务一致。
- **超时补偿**：定时扫描 30 分钟未支付订单，使用条件状态更新避免重复取消和重复归还库存。
- **三角色 JWT 鉴权**：按用户、商家、骑手设置身份上下文，业务查询继续按主体 ID 做数据归属过滤。
- **履约快照**：订单和配送任务保存商品、商家、收货人、地址和联系方式快照，降低基础数据变化对历史业务的影响。
- **多层测试**：包含 MockMvc API 测试、Service 单元测试、H2 隔离测试以及 MySQL/Redis 并发与集成测试。

## 11. 已知限制

- `PATCH /order/{id}/pay` 是本地模拟支付，没有接入支付网关、回调验签、退款、对账或消息投递。
- Redis 下单幂等仍是实验服务，没有接入 `OrderController`。
- 项目没有 Flyway/Liquibase，`deploy/schema.sql` 只能初始化空库，不能承担版本升级和 schema 漂移校验。
- 核心关联大多没有数据库外键，部分关联 ID 的有符号/无符号定义也不完全一致，完整问题见数据库设计检查报告。
- “单商家购物车”主要由“先查冲突再写入”保证；空购物车并发加入不同商家商品时仍存在竞态，下单阶段会识别并拒绝多商家购物车。
- 购物车 `+1/-1` 是数量指令，没有请求幂等键；客户端重复发送会重复累计。
- 配送奖励目前固定为 `5`，尚未实现距离、区域、时段和骑手结算规则；配送任务列表也没有显式排序。
- 角色控制依赖自定义拦截器的路径匹配。新增接口时必须同步检查 `AuthPathMatcher`，否则可能只要求“任意有效 JWT”而没有精确角色限制。
- `compose.yaml` 虽已配置依赖健康检查和 MySQL 数据卷，但仍使用固定的本地演示凭据，未包含密钥管理、备份、资源限制或高可用配置，不适合生产部署。
- MySQL 集成测试的地址和 `root/root` 凭据写在测试代码/配置中，并会重建专用测试表，不适合直接用于共享环境。

## 12. 其他文档

以下文档均位于仓库根目录：

| 文档 | 作用 |
|---|---|
| [`HELP.md`](HELP.md) | 开发快速入口、常用命令和官方资料链接 |
| [`IdempotencyDesign.md`](IdempotencyDesign.md) | 订单及其他接口的幂等、重复请求和 Redis 实验设计 |
| [`数据库结构.md`](数据库结构.md) | 九张业务表的字段、索引、外键和关系说明 |
| [`数据库设计检查报告.md`](数据库设计检查报告.md) | 数据库约束、索引、迁移和完整性问题检查 |
| [`状态流转表.md`](状态流转表.md) | 订单与配送任务状态、动作契约和最小履约闭环 |
| [`项目暂缓风险清单.md`](项目暂缓风险清单.md) | 当前明确暂缓、接受或需要重新评估的风险边界 |

文档可能记录不同阶段的审计结论；当文档与实现不一致时，应以当前代码、数据库脚本和可重复测试结果为准。
