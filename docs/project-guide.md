# 项目说明与运行指南

最近复核：2026-10-26。本文件由原根目录 README 移入；所有命令仍在项目根目录执行。此次核对当前工作区源码、SQL、配置及已有测试，未运行应用或业务测试。全部专题文档见[文档索引](README.md)。

Tokeout 是一个基于 Spring Boot 的外卖后端练习项目。项目围绕用户、商家、骑手三类角色，实现了店铺与商品浏览、购物车、订单、商家履约和骑手配送的最小业务闭环。

> 本仓库当前只包含后端服务，不包含 Web、App 或小程序前端。以下说明以当前工作区中的 Controller、Service、配置文件、`deploy/schema.sql` 和测试代码为准。

## 1. 项目介绍

项目包含三类业务角色：

- 用户：注册登录、浏览店铺与商品、维护购物车、创建/支付/取消订单、查询订单和确认收货；
- 商家：注册登录、维护店铺状态、管理分类与商品、查看订单、接单、拒单和确认出餐；
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
| 缓存/限流 | Spring Data Redis、Redis 7 |
| 对象转换 | MapStruct 1.5.5、Lombok 1.18.30 |
| 接口文档 | Knife4j 4.5.0、OpenAPI 3 |
| 测试 | JUnit 5、Spring Boot Test、MockMvc、H2、MySQL/Redis 集成测试 |
| 部署 | Docker、Docker Compose |

Spring Security 的过滤链目前配置为无状态并放行请求，实际 JWT 解析、公开路径和角色限制由 `JwtInterceptor` 与 `AuthPathMatcher` 完成。

## 3. 模块结构

```text
.
├─ docs/                            项目说明、架构、Redis、设计及学习材料
├─ deploy/
│  ├─ schema.sql                    MySQL 8 空库初始化脚本
│  └─ demo-data.sql                 可重复执行的本地演示数据
├─ src/main/java/org/example/takeout/
│  ├─ User/                         用户注册、登录、账号状态校验
│  ├─ Merchant/                     商家账号、店铺查询、商家订单履约
│  ├─ Category/                     商家商品分类
│  ├─ Product/                      商品、上下架、库存与商品缓存代码
│  ├─ Cart/                         购物车增删改查与可下单校验
│  ├─ CartHeader/                   购物车总表初始化、行锁与商家绑定
│  ├─ Order/                        下单、支付、取消、查询、状态流转与超时任务
│  ├─ Rider/                        骑手账号、GEO 位置服务与过期清理
│  ├─ DeliveryTask/                 配送任务创建、抢单、送达与查询
│  ├─ CacheInvalidationTask/        持久化缓存失效任务、重试及历史清理
│  ├─ Experiment/                   独立的 Redis Hash 购物车/Set 关注实验
│  ├─ Common/                       鉴权、上下文、异常、统一响应、登录限流和工具类
│  ├─ Config/                       拦截器、Security、JWT、MyBatis-Plus 配置
│  └─ TokeoutApplication.java       应用入口，启用定时任务
├─ src/main/resources/
│  ├─ application.yaml              主配置
│  ├─ db/migration/                 Flyway 版本化迁移脚本
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
| `Merchant` | 商家注册登录、店铺资料/营业状态、店铺查询、订单查询与接单/拒单/出餐、SSE 提醒与 NEW 标记确认 |
| `Category` | 分类创建、查询、删除；删除普通分类时将商品迁移到默认分类 |
| `Product` | 商品创建、列表、上下架、恢复、库存条件扣减及缓存相关服务 |
| `Cart` | 单商家购物车校验、商品增减、列表、批量删除与清空 |
| `CartHeader` | 用户购物车总表初始化、行锁与单商家绑定 |
| `Order` | 订单创建幂等、事务下单、支付/取消/确认、取消来源、订单查询、超时取消 |
| `Rider` | 骑手注册、登录和状态校验；GEO 位置服务及清理任务已有实现，尚未接入 HTTP 和派单流程 |
| `CacheInvalidationTask` | 商品变更时登记失效任务、提交后快删、定时补偿、SUCCESS 历史清理 |
| `Experiment` | Redis Hash 购物车/Set 关注实验；未替换正式 MySQL 购物车 |
| `DeliveryTask` | 出餐后建任务、可接任务、抢单、当前任务、详情与完成配送 |
| `Common` | JWT 鉴权、ThreadLocal 身份上下文、异常、响应模型和登录限流 |

## 4. 核心业务流程

### 4.1 注册、登录与鉴权

用户、商家、骑手分别通过自己的注册和登录接口进入系统。密码使用 BCrypt 保存；登录成功后签发带有主体 ID 和角色的 JWT。除公开路径外，请求需携带：

```http
Authorization: Bearer <token>
```

公开路径主要包括三类角色的注册/登录、顾客端店铺浏览，以及 Knife4j/OpenAPI 文档。购物车和订单只允许用户访问；分类和商家履约接口只允许商家访问；配送任务只允许骑手访问。

受保护请求的角色分类使用 Spring MVC 的 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`（已经匹配到的路由模板），不再用原始 URI 判定角色；取不到模板时拒绝请求。因此 `/cat%65gory/products/image` 映射到商品上传接口时，仍按 `/category/products/image` 要求商家身份。公开白名单仍由 `AuthPathMatcher.isPublicPath` 判断。当前上传服务没有另加商家身份检查，角色拦截与业务资源归属检查各有自己的范围。

三类登录名（`user.username`、`merchant.username`、`rider.name`）继续使用 `utf8mb4_0900_as_cs`。三个角色的注册、登录共六个 DTO 使用同一 `@Pattern` 白名单，只接受英文字母、数字、下划线和连字符，长度仍为 1–50；禁止空格和其他 Unicode 字符。非法输入在现有 `@Valid` 参数校验阶段拒绝，不进入账号查询或 Redis 登录限流。展示名称不受此登录名规则影响；Redis 原文 key、账号存值、密码和 ID 不变。

### 4.2 店铺、商品与购物车

1. 商家注册时会在同一事务内创建“默认分类”。
2. 商家创建分类和商品，并对商品执行上架/下架操作。
3. 用户通过 `/api/customer/shops` 浏览店铺及已上架商品。
4. 用户将商品加入购物车。业务上限制一个购物车只包含同一家店铺的商品。
5. 购物车列表会复核商品状态和商家营业状态，并给出是否可下单及失效原因。

当前商品规则区分创建、更新和上架：

| 操作 | 当前规则 |
|---|---|
| 创建 | 名称非空白且不超过 15 字；价格大于 0，最多 8 位整数、2 位小数；库存非负，创建 DTO 尚未设置业务上限 |
| 更新 | `PATCH /category/products/{id}` 部分更新，字段均可省略，但 `version` 必填；未提交 `stock` 时不修改库存和 `stock_version`；显式提交 `stock` 时按绝对值重设库存基准，即使数值与旧库存相同也 `stock_version + 1`；提交名称时必须非空白；价格允许 0 至 99999.99 及额外小数位，库存允许 0 至 10000 |
| 上架 | 从下架或售罄转为在售时仍要求价格大于 0、名称有效；在售商品更新为零价后不会自动下架 |

以上差异是当前明确接受的业务规则。商品价格最终由 MySQL `DECIMAL(10,2)` 保存为两位小数，更新请求中的额外小数位不会原样保留；例如 `18.805` 落库为 `18.81`。商家编辑详情直接读取落库后的价格和版本。

`product.stock_version` 是独立的库存基准版本，不复用乐观锁 `version`。订单创建成功预扣库存时，`order_item.stock_version` 记录的是该次扣减对应的商品库存版本，而不是购物车准备阶段可能过期的快照。取消订单（用户取消、商家拒单、超时取消）回补库存时，只有两个版本一致才执行 `stock = stock + quantity`；商家已重设库存导致版本不一致时跳过回补。商家每次显式提交 `stock` 都会推进 `stock_version`，商品名称/价格修改、普通预扣和回补都不会推进该版本。

未提供图片时使用可匿名访问的 `/images/default-product.svg`。孤儿图片清理每轮结束后等待 12 小时，只扫描修改时间超过 24 小时的文件；所有商品引用（包括逻辑删除、可恢复商品）都保护原图。只有不再被商品行引用的候选才被回收，软删引用的长期存储及历史订单图片保留属于后续资源治理边界。

图片上传校验 5 MB 文件大小、JPEG/PNG MIME、后缀及 ImageIO 内容，但尚未在解码前限制宽高或总像素。小文件仍可能解压出很大的图像，是尚未修复的 P1，不能把文件字节上限表述成解码内存上限。

### 4.3 创建订单

`POST /order` 使用客户端提供的 `requestId` 表示一次下单意图，实际生效的链路如下：

1. 按 `(userId, requestId)` 查询已有订单；命中时比较收货人、手机号、地址和备注的 SHA-256 指纹，相同则返回原订单，不同则拒绝请求并提示使用新的 `requestId`。
2. 读取购物车并确认所有商品均可售、商家可下单且购物车只有一个商家。
3. 使用商品当前价格计算总金额；超过 `1000000` 时在落单前返回业务错误。
4. 在事务内插入订单及请求指纹、锁定用户购物车总表，按快照行 ID 和数量条件消费购物车，仅删除数量归零的记录，然后按条件扣减库存、写入订单明细。
5. `orders(user_id, request_id)` 唯一键处理相同请求的并发竞争；发生冲突后再次比较已提交订单的指纹，相同则返回原订单，不同则拒绝请求。事务确保订单、购物车、库存和明细一起提交或回滚。

请求指纹覆盖 `CreateOrderDTO` 的收货信息和备注，备注的 `null` 与空字符串视为不同参数；购物车内容以第一次成功下单时的快照为准。

`request_id` 当前仍使用 `utf8mb4_0900_ai_ci`，查询及唯一键会把 `demo-a` 与 `DEMO-A` 等字符串视为同一标识，而 DTO 没有限制为规范 UUID。这项比较语义尚未修复，属于 P1；客户端不能仅靠改变大小写或重音表示新的下单意图。

首次查询未命中后，如果准备阶段返回 `CART_EMPTY`（购物车为空或包含不可用商品），服务再按原 key 查询一次已提交订单，找到后核对指纹并重放；没找到则保留准备错误。其他准备阶段业务异常不走此补查，不能将其描述为任意准备失败都能重放。

订单采用准备阶段的商品价格、名称和商家资料/营业状态快照；允许准备与提交之间发生改价或打烊。写入时仍通过条件更新复核商品未删除、在售、库存及购物车数量。这是已接受的快照语义。

库存扣减 SQL 同时校验商品未删除、处于上架状态且库存充足，从数据库层避免超卖；扣减前先锁定商品行并读取库存版本，订单明细保存的版本与实际扣减处于同一行锁区间。取消订单归还库存时 SQL 带 `stock_version` 条件，版本不一致的明细直接跳过，不会把旧订单预扣量叠加到商家新设定的库存基准上。待支付订单创建超过 30 分钟后，定时任务会尝试取消订单并归还库存；默认每 60 秒扫描一次，每批最多处理 100 个订单。

### 4.4 订单与配送状态流转

```text
WAIT_PAY
  ├─ 用户取消 / 30 分钟超时 -> CANCELLED（归还库存，cancel_source=1/3）
  └─ 模拟支付 -> PAYING -> PAID
       ├─ 商家拒单 -> CANCELLED（模拟退款 + 归还库存，cancel_source=2）
       └─ 商家接单 -> PREPARING
            -> 商家确认出餐 -> READY + 创建 WAIT_ASSIGN 配送任务
            -> 骑手抢单 -> DELIVERING
            -> 骑手确认送达 -> DELIVERED + 配送任务 COMPLETED
            -> 用户确认收货 -> FINISHED
```

商家拒单只允许从 `PAID` 发起，在同一事务内把订单改为 `CANCELLED`、记录 `cancel_source=2`、模拟退款并归还库存；同一商家重复拒单幂等返回，不会重复退款或归还库存。接口为 `PATCH /merchant/orders/{orderId}/reject`，仅允许商家身份。

商家出餐、骑手抢单和骑手送达会在各自事务内同步推进订单与配送任务。配送任务保存商家、收货人、电话、地址和配送奖励快照，避免履约过程中基础资料变化影响已有任务。

`FulfillmentService.completePreparation`、`DeliveryTaskService.claimTask` 和 `completeDelivery` 三个事务入口局部使用 `READ_COMMITTED`，避免重复请求在预先读取账号/商家之后，继续用旧的可重复读快照校验另一个事务已推进的订单或任务。归属、旧状态条件更新及失败回滚仍保留；没有修改全库隔离级别，也没有把所有已推进状态都改为可重放成功。

对外 VO 中的业务状态统一同时返回数字 `status` 和中文 `statusDesc`，例如 `{"status":0,"statusDesc":"待支付"}`。数字状态用于程序判断和接口筛选，状态文案用于页面展示；无法识别的状态码返回“未知状态”并记录告警。

### 4.5 商家新订单提醒与 NEW 标记

用户支付成功的事务提交后，后端尝试通过 SSE 提醒在线商家刷新订单。订单的 `merchantSeen` 字段持久化 NEW 标记：只有 `PAID` 且 `merchantSeen = false` 的订单需要提示为新订单；商家接单后，该订单便离开待接单提醒范围。

| 接口 | 行为 |
|---|---|
| `GET /merchant/events` | 建立 `text/event-stream` 连接；先发送 `CONNECTED: connected`，新订单发送 `NEW_ORDER: refresh`，每 30 秒发送 `heartbeat` 注释 |
| `GET /merchant/orders?type=PENDING` | 获取待接单完整订单，列表包含 `merchantSeen` |
| `GET /merchant/orders/unseen` | 按 `create_time ASC, id ASC` 返回当前商家未查看的 `PAID` 订单 ID |
| `PATCH /merchant/orders/seen` | 请求体为订单 ID 数组，如 `[101,102]`；仅更新当前商家仍为 `PAID` 且未查看的订单，重复 ACK 无额外影响 |

上述接口仅允许商家身份，SSE 也必须携带 `Authorization: Bearer <token>` 请求头，不支持把 Token 放入 URL。前端需使用能携带该请求头的 SSE 客户端，并设置 `Accept: text/event-stream, application/json`，同时接受正常事件流和鉴权失败的 JSON 结果。当前鉴权失败响应沿用 HTTP 200、业务 `code=401` 的约定；前端应检查响应类型及业务码，不把所有 HTTP 200 都当作建连成功。仅接受 `text/event-stream` 的严格 SSE 客户端尚无兼容保证。`JwtInterceptor.afterConcurrentHandlingStarted` 会在 SSE 转入异步处理后清理原请求线程的商家身份上下文。

本仓库只有后端，以下是前端仍需实现的接入契约：首次打开页面及 SSE 重连后补查 `/merchant/orders/unseen`；收到 `NEW_ORDER` 后刷新订单；结合 `merchantSeen` 展示 NEW；订单实际进入可见界面后才提交 ACK。发送 SSE 或查询订单均不会自动标记已查看，设计理由见 [NEW 标记 ACK 决策](merchant-order-new-marker-ack-decision.md)。

当前每个商家在每个应用进程中只保存一条 SSE 连接，新连接会完成旧连接，多标签页会互相替换；连接表保存在内存中，支付请求只通知其所在进程的连接。SSE 不持久化事件，也不保证浏览器收到或展示，重启、断线和多实例分流后的恢复依赖前端主动补查。该方案尚未实现多端广播、跨实例通知或事件回放。

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

上述是部署文件的预期流程。本次文档核查未实际运行 Docker Compose，不能据此声称已经验证干净环境一键启动。

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

# 真 Redis 相关集成测试（部分套件同时依赖 MySQL）
mvn test "-Dtest=RedisBasicIntegrationTest,RedisLoginAttemptStoreIntegrationTest,OrderCreateRateLimiterIntegrationTest,RiderLocationServiceIntegrationTest,RedisHashCartServiceExperimentIntegrationTest"
```

默认测试配置使用 H2，但多组集成测试会直接连接 `localhost:3306/takeout_integration_test`，并使用 `root/root`。其中 `MysqlApiIntegrationTest` 会反复删除并重建九张核心业务表、重建购物车总表，并创建/清空缓存失效任务表，只能对专用测试库运行。Redis 不可用时，Redis 专用测试通过 JUnit assumption 跳过；要覆盖 Redis 行为则需提供 `127.0.0.1:6379`。如需让测试连接 Compose 中的 MySQL，应先确认宿主机 `3306` 未被占用，再设置 `$env:MYSQL_PORT = '3306'` 后启动依赖容器。

商家 SSE、NEW 标记 ACK 和支付提交后通知的定向回归可单独运行：

```powershell
mvn test "-Dtest=MerchantSseServiceTest,MerchantSseMvcTest,MerchantOrderSeenDatabaseTest,OrderPaymentNotificationTransactionTest"
```

`MerchantSseServiceTest`、`MerchantSseMvcTest` 分别隔离验证 SSE 服务和 MVC 鉴权/异步上下文清理；`MerchantOrderSeenDatabaseTest` 使用真实 H2 Mapper 与 MVC 验证未查看查询/ACK 闭环，`OrderPaymentNotificationTransactionTest` 使用真实 H2 SQL 和 Spring 事务验证支付提交、回滚的通知时机。这四个套件无需 MySQL 或 Redis，不能替代 `V20261017` 在真实 MySQL 上的迁移、历史数据回填及索引验证。

可按以下类名规则选择单元/API 与 H2 回归，排除 `*IntegrationTest` 套件：

```powershell
mvn test "-Dtest=*Test,!*IntegrationTest"
```

三个局部 RC 事务的真实 MySQL 时序回归可单独运行：

```powershell
mvn test "-Dtest=MysqlFulfillmentRetryIntegrationTest"
```

该套件创建随机命名的独立测试数据库，结束时删除，不使用现有 `takeout` 或 `takeout_integration_test`。账号需有创建/删除测试数据库权限，默认连接 `localhost:3306`、`root/root`；可通过 `takeout.fulfillment-test.mysql.host/port/user/password` 系统属性覆盖。测试固定连接池默认 RR，验证生产 Service 的三个局部 RC 注解，不修改服务器全局隔离级别；这组回归不依赖真实 Redis，也不验证 Flyway 迁移。

`FlywayMigrationIntegrationTest` 默认跳过，显式启用后验证旧库升级、空库初始化、历史数据保留、异常数据预检及演示数据重复导入。每个用例创建随机命名的 `takeout_migration_test_*` 数据库并在结束时清理，需要 MySQL 账号具备创建和删除测试库的权限；不会使用 `takeout` 或 `takeout_integration_test`：

```powershell
mvn -Dtest=FlywayMigrationIntegrationTest "-Dtakeout.migration-test.enabled=true" test
```

## 7. 数据库初始化

仓库提供 [`deploy/schema.sql`](../deploy/schema.sql)，用于创建 `takeout` 数据库及以下十一张业务表：

```text
user
merchant
category
product
cart
cart_header
orders
order_item
rider
delivery_task
cache_invalidation_task
```

配置所指向的数据库需要先存在。已创建的空数据库可直接启动应用：Flyway 会执行 `B20261008__initial_schema.sql` 累计基线，再应用更高版本的迁移，无需预先运行 `schema.sql`。已有迁移历史的数据库会忽略 `B` 脚本，继续按历史升级，详见 [Flyway 累计基线文档](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations)。

也可在项目根目录手工初始化数据库和表：

```powershell
mysql -u root -p --execute="source deploy/schema.sql"
```

已有数据库由应用启动时的 Flyway 迁移，权威脚本位于 `src/main/resources/db/migration`，命名格式为 `V<版本>__<描述>.sql`。非空旧库尚无迁移历史时，当前配置会先建立版本 `1` 的基线；随后按版本顺序执行尚未成功应用的脚本，并将版本、校验和及执行结果写入 `flyway_schema_history`。已有成功记录的版本会校验内容并跳过执行。

当前版本范围为 `V20260820` 至 `V20261026`，权威目录及部署镜像各有 18 份 SQL（1 份 `B`、17 份 `V`）：`V20261007` 调整用户登录名排序规则及订单索引，`V20261008` 增加超时取消退避字段和扫描索引，`V20261009` 补齐配送奖励及骑手、配送任务自增主键，`V20261010` 回填购物车总表，`V20261011` 修正运行时默认值及商品分类约束，`V20261012` 将商家和骑手登录名改为 `as_cs`。历史 `V20261013` 二进制排序迁移已执行过，保留原内容；`V20261014__restore_login_name_as_cs.sql` 在三类名称合并冲突预检通过后恢复三列 `utf8mb4_0900_as_cs`，当前合法输入由六个 DTO 的统一白名单限制。`V20261017__merchant_order_seen.sql` 增加订单 `merchant_seen` 及商家未查看订单查询的五列复合索引；缺失标记的历史订单按状态 `0/1/2` 回填 `false`，其他状态回填 `true`，已有 `true` 保留，且不推进 `update_time`。`V20261018__order_cancel_source.sql` 为订单增加可空 `cancel_source`，记录 `1` 用户主动取消、`2` 商家拒单、`3` 系统超时取消、`4` 平台取消；历史已取消订单保持 `NULL`，新取消由订单代码写入对应来源。最新 `V20261026__stock_version.sql` 为 `product` 和 `order_item` 增加独立的 `stock_version`，历史行默认 `0`；仅商家显式重设库存时推进商品基准，取消回补按明细版本条件执行。空库执行 `B20261008` 后应用 `V20261009` 至 `V20261026` 共 9 份后续迁移；不改写已执行版本的校验和。历史配送任务奖励快照仍按原规则回填，已有值保持不变。

购物车回填和分类约束迁移使用临时表的命名 `CHECK` 约束预检旧数据。失败时错误会指出具体约束，含义及排查 SQL 见 [数据库迁移说明](database-migrations.md)。应先处理对应数据问题，再按失败迁移恢复流程重试；不要修改已成功脚本或使用 `repair` 更新其校验和来绕过检查。

[`deploy/migrations`](database-migrations.md) 是上述 `B`、`V` 脚本的同名、同内容镜像，供部署审阅使用，应用只扫描 classpath 目录。不要在 Flyway 已记录成功的版本上再次手工执行同一变更，也不要修改其文件名或内容。新增数据库变更时，应增加更高版本的新脚本，并同步部署镜像。`schema.sql` 负责手工或 Compose 空库初始化；修改它不会升级已有数据库或数据卷。

可用下面的 SQL 检查迁移状态：

```sql
SELECT installed_rank, version, description, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

### 7.1 导入演示数据

完成建表后，可继续执行 [`deploy/demo-data.sql`](../deploy/demo-data.sql)：

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
- 应用启动时由 Flyway 执行 `src/main/resources/db/migration` 中尚未应用的版本；
- `schema.sql` 不会被 Flyway 自动扫描，也不会重复升级已有数据卷。

商家通过注册接口创建后会自动获得默认分类，不需要手工插入默认分类数据。

## 8. Redis 的接入范围与故障边界

订单唯一键、库存条件更新、订单与配送事务的正确性依赖 MySQL。Redis 为部分接口提供缓存和限流；新增骑手位置服务则直接依赖 Redis，当前尚无 HTTP 或派单业务接入。

| 用途 | 当前接入与故障行为 |
| --- | --- |
| 商品详情缓存 | `GET /category/products/{id}` 使用 `ProductDetailCacheService`；读取或抢锁异常由详情入口降级读 MySQL，回填/解锁失败记录日志。商品写事务通过持久化失效任务补偿 DEL，不能统一称为“所有 Redis 故障都回源” |
| 登录失败限制 | 三角色共用 `RedisLoginAttemptStore`/`LoginAttemptLimiter`，失败达到 5 次后锁定 300 秒；故障时继续账号密码校验，但限流保护减弱 |
| 创建订单限流 | `POST /order` 按用户执行 ZSET + Lua 滑动窗口，10 秒最多放行 5 次，key TTL 15 秒；故障或脚本空结果时放行。幂等仍由数据库唯一键、请求指纹和事务保证 |
| 骑手位置服务 | `RiderLocationService` 使用 `rider:locations` GEO 与 `rider:last_active` ZSET；Lua 更新/移除两处记录。附近查询按距离升序最多 100 条；Redis 命令异常及写入/附近查询/清理无结果等情况抛 `RedisGeoOperationException`，映射为 SERVER_ERROR；单人位置无记录返回空，没有 MySQL 位置回源 |
| 独立学习实验 | Hash 购物车与 Set 关注位于 `Experiment` 包，未接入正式 Controller；Redis Stream、Pub/Sub、Redisson 看门狗及 MQ 尚未接入 |

商品缓存重建锁的 key 为 `Lock:product:detail:{id}`，租期固定 10 秒，UUID + Lua 只保护解锁所有权，当前没有自动续期。慢读越过租期仍可能与新请求并行回填；架构 F02 接受这个窗口，不能据此宣称缓存强一致。看门狗对应的具体位置及边界见 [Redis 学习日志](REDIS_LEARNING_LOG.md)。

缓存失效任务在业务事务中创建，提交后立即尝试删除但仍保留 PENDING；正常短周期失败第 1～4 次分别退避 10/30/60/120 秒，第 5 次进入 FAILED，保留原到期时间供每小时扫描。SUCCESS 历史清理每轮完成后等待 1 小时，只删除创建满 7 天的记录，按创建时间与 ID 升序最多 1000 条；不是按成功时间保留 7 天。

骑手位置超过 5 分钟未上报后，由每轮完成后等待 5 分钟的清理任务删除；每轮最多 10 批、每批 100 人。查询没有即时过滤最后活跃时间，因此不是严格的五分钟在线名单，也不是给 GEO 成员设置独立 TTL。服务端经纬度校验采用 ±180/±90，超出 Redis GEO 实际支持纬度的输入仍可能在 Redis 层失败；当前坐标、半径和接口接入边界见 Redis 日志。

应用配置没有将 Redis 连接设为启动时硬校验，但清理任务可能记录 Redis 不可用日志；Docker Compose 的 app 仍等待 Redis 健康。验证缓存、限流、位置服务和独立实验，需要可用 Redis。正式交易写入不应使用缓存中的 `inStock` 代替数据库库存判断。

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

- **数据库级订单幂等**：`requestId` 与用户 ID 组成唯一键，相同业务请求并发执行时最终只生成一张订单；收货信息或备注不同的重复请求会被拒绝。
- **事务化下单**：订单、购物车消费、条件扣库存和订单明细处于同一事务，任一失败则整体回滚。
- **并发控制**：库存使用带库存下限、商品状态和库存基准版本的条件更新；下单预扣先在行锁内读取版本，取消回补再以该版本为条件，避免旧订单覆盖商家新基准。购物车、商家、商品使用唯一约束、版本列或条件更新避免常见竞态。
- **最小履约状态机**：订单状态与配送任务状态分离，并在出餐、抢单、送达等关键动作中保持事务一致。
- **超时补偿**：定时扫描 30 分钟未支付订单，使用条件状态更新避免重复取消；归还库存仍受 `stock_version` 约束，不会重复归还或覆盖重设后的基准。
- **商家订单提醒**：支付事务提交后尝试 SSE 唤醒，持久化 NEW 标记支持前端在首次打开或重连时补查，并在展示后 ACK。
- **持久化缓存失效任务**：商品修改与 `PENDING` 缓存失效意图在同一本地事务中提交，Redis 删除失败后由定时任务退避重试。
- **三角色 JWT 鉴权**：按用户、商家、骑手设置身份上下文，业务查询继续按主体 ID 做数据归属过滤。
- **履约快照**：订单和配送任务保存商品、商家、收货人、地址和联系方式快照，降低基础数据变化对历史业务的影响。
- **多层测试**：包含 MockMvc API 测试、Service 单元测试、H2 隔离测试以及 MySQL/Redis 并发与集成测试。

## 11. 已知限制

- `PATCH /order/{id}/pay` 与商家拒单中的退款都是本地模拟（`paying()`/`refunding()` 恒为 `true`），没有接入支付网关、回调验签、真实退款、对账或持久化消息投递。
- 商家 SSE 仅提供当前进程内的一商家一连接提醒；多标签页、多实例通知和事件可靠回放尚未实现，前端补查及展示后 ACK 也需由独立前端接入。
- Flyway 迁移使用应用启动时的数据库连接；`deploy/schema.sql` 只能初始化空库，不能替代版本迁移。
- 核心关联大多没有数据库外键，部分关联 ID 的有符号/无符号定义也不完全一致；当前入口如何维护关系、哪些异常仅在数据不变量被破坏后出现，见[当前项目风险与架构复核](当前项目风险与架构复核_2026-10-11.md)。
- 单商家购物车由注册事务初始化的 CartHeader、同一用户 Header 行锁和商品真实归属维护；正常并发加购不会持久化跨商家购物车。准备阶段的多次读取仍可能交错，写入时按旧行 ID 和数量条件消费，变化不满足条件则整体回滚。
- 购物车 `+1/-1` 是数量指令，没有请求幂等键；客户端重复发送会重复累计。
- 配送奖励目前固定为 `5`，尚未实现距离、区域、时段和骑手结算规则；配送任务列表也没有显式排序。
- 角色控制依赖自定义拦截器对 MVC 路由模板的分类。新增接口时必须同步检查 `AuthPathMatcher` 的角色规则，否则可能只要求“任意有效 JWT”而没有精确角色限制。
- 尚未修复的 P1 包括下单 `request_id` 的不精确比较及图片解码前无像素上限；详情和最小修复思路见当前风险复核。
- `compose.yaml` 虽已配置依赖健康检查和 MySQL 数据卷，但仍使用固定的本地演示凭据，未包含密钥管理、备份、资源限制或高可用配置，不适合生产部署。
- MySQL 集成测试的地址和 `root/root` 凭据写在测试代码/配置中，并会重建专用测试表，不适合直接用于共享环境。

## 12. 其他文档

全部文档已集中到本目录；完整导航见[文档索引](README.md)，本次过期内容修正见[核查记录](documentation-review-2026-10-26.md)。当前风险结论集中维护：

| 文档 | 作用 |
|---|---|
| [`IdempotencyDesign.md`](IdempotencyDesign.md) | 订单及其他接口的幂等、重复请求与业务边界 |
| [`merchant-order-new-marker-ack-decision.md`](merchant-order-new-marker-ack-decision.md) | 商家 SSE、NEW 标记、前端展示后 ACK 的接入契约与边界 |
| [`数据库结构.md`](数据库结构.md) | 十一张业务及基础设施表的字段、索引、外键和关系说明 |
| [`状态流转表.md`](状态流转表.md) | 订单与配送任务状态、动作契约和最小履约闭环 |
| [`当前项目风险与架构复核_2026-10-11.md`](当前项目风险与架构复核_2026-10-11.md) | 当前源码风险、触发条件、已接受边界和冻结判断 |
| [`architecture-refactoring-history.md`](architecture-refactoring-history.md) | 历史架构演进、职责边界与事务契约；不作为当前未修风险清单 |
| [`REDIS_LEARNING_LOG.md`](REDIS_LEARNING_LOG.md) | Redis 学习进度、实现边界及后续 MQ 学习路线 |
| [`项目面试准备报告.md`](项目面试准备报告.md) | 面试表达和复习顺序；历史核查以当前报告复核 |

文档可能记录不同阶段的审计结论；当文档与实现不一致时，应以当前代码、数据库脚本和可重复测试结果为准。
