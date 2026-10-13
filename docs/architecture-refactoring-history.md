# 架构重构历史与验证记录

首次整理：2026-10-11（Asia/Shanghai）。核查基准：`e72686c98b90ce2283d0881e085abe2233506c77` 加当日工作区。本文由 Git 差异、提交前后源码和当前源码回溯生成，不以提交标题或待办项被删除作为完成证据。

**本文保留历史重构和当时的验证记录，不作为当前未修风险清单。** 当前源码风险、A/B/C/D/E 定性及冻结判断统一以[当前项目风险与架构复核](../当前项目风险与架构复核_2026-10-11.md)为准；以下历史 D/R 状态不能直接沿用。

同日早期再次复核：第 2–3 节及第 5.1–5.3 节保留首次回溯、09:44 测试运行时的工作区快照；第 4.2 节记录当时续审状态，随后代码变化与验证缺口见第 5.4 节。09:51/10:02/10:07 的后续未提交修改不能借用 09:44 的通过结果。当时续审仅更新文档，未修改业务或测试、未再次运行 Maven；后续复核与修复不回写成这次历史测试已覆盖。

## 1. 背景、目标与证据口径

此前架构检查报告侧重问题清单，条目删除、结论更新后不容易还原已完成的工作。本文保留每项改动的来源、当前状态和验证范围，供后续增量维护。

主要职责重构集中在 **2026-10-06 至 2026-10-09** 的 `90af04a`、`e72686c`；缓存、可靠性与注入方式的前置演进追溯至 **2026-08-28 至 2026-09-29**。必要时引用更早的能力来源，避免将已有能力误记为本轮新设计。时间取 Git 提交时间（+08:00）；SQL 文件中的版本日期不等于实现日期，例如 `V20261011` 已包含在 10 月 9 日的 `e72686c` 中。

已提交的《架构分层检查报告_更新版.md》（`e72686c`）明确采用分层单体，按业务所有权和完整职责拆分，不强制所有跨模块调用都通过 Command/Query。实际核查重点是 Mapper 穿透、规则归属、依赖环、事务及并发契约。《架构分层检查报告_2026-10-10晚间更新.md》曾作为首次回溯线索并在同日续审中更新；后续风险重新按可达性验证，旧报告现已归并至当前风险复核，不再维护独立结论。

状态含义：

| 状态 | 本文含义 |
| --- | --- |
| 已完成 | 本条限定的迁移在当前源码中闭环，旧入口和调用已核对；测试是否通过另列 |
| 部分完成 | 已有实现，但本条整体目标仍有明确残留 |
| 待处理 | 当前仍有可定位的问题或验证缺口 |
| 无法确认 | Git/源码/本次运行不能证明的意图或历史结果，不作推测 |

**工作区标记 `WT-20261011` 没有对应 commit hash。** 它表示本次开始时已存在的已暂存、未暂存或未跟踪改动；不能归到 HEAD。此次仅修订/新增测试和本文，保留原有业务代码、暂存状态及报告改动。历史提交包含测试修改只能证明测试已更新；历史测试是否执行并通过，在没有绑定该版本的运行证据时均为「无法确认」。本次实际验证集中记录于第 5 节。

回溯使用的命令（项目根目录执行）：

```powershell
git log --date=iso-strict --format="%H %ad %s"
git show --find-renames <hash> -- src/main src/test
git diff <hash>^ <hash> -- <path>
git show <hash>^:<path>
git show <hash>:<path>
git diff HEAD -- src/main src/test
git diff --cached
rg -n '旧类名|旧方法名' src/main src/test
```

## 2. 按模块还原的重构

本节每条的“完成”只针对所述迁移，不能解读为整个模块已无技术债。测试类与执行状态见第 5 节。

### Product / Category

**P01：库存业务从 ProductService 迁入 ProductCommandService — 已完成。**

- 原问题：`ProductService` 同时处理商品 CRUD、库存条件更新、缓存及图片；跨模块库存规则集中在这个大 Service 中。
- 实际变化：`e72686c` 删除 `ProductService.decreaseStock/increaseStock` 等实现，新增 [ProductCommandService](../src/main/java/org/example/takeout/Product/Service/ProductCommandService.java) 的 `decreaseStock`、`increaseStock`、`evictCacheIfInStockChanged`，订单项经该组件扣/退库存。命令组件直接依赖 `ProductMapper` 与 `ProductDetailCacheService`，没有反向注入 `ProductService`。
- 保留契约：两个库存入口保留 `@Transactional(rollbackFor = Exception.class)`；扣减仍限定未删除、在售和足够库存，更新库存/售罄状态/版本；退库回读继续包含逻辑删除商品。这些规则已在 `1653171` 演进，不能记为 `e72686c` 首次新增。
- 残留：生产代码无旧 Service 库存入口引用；动态 `setSql` 扣减语句仍在命令层，参数化 Mapper 下沉尚未完成。模块依赖 `ProductService -> CategoryService -> ProductCommandService` 仍存在，但该链路不返回 `ProductService`，不能将模块互相引用等同于 Bean 构造器环。
- 测试：`ProductCommandServiceTest`、`OrderItemServiceTest` 随 `e72686c` 新增/更新；本次验证见 T-P。

**P02：详情缓存独立成职责组件，调整 null 回源与回填失败契约 — 已完成。**

- 原问题：`434a0af` 后的 `ProductService` 自行处理 JSON、空值、锁、重试、TTL、回源与 Redis 降级，业务查询和缓存算法耦合。`e72686c^` 的旧 `loadProductDetailAndCache` 已先写空值标记再抛异常，不能把旧版本描述为空值回填不可达。
- 实际变化：`e72686c` 新增 [ProductDetailCacheService](../src/main/java/org/example/takeout/Product/Service/ProductDetailCacheService.java)，`getProductDetailCache` 接收 `Supplier<ProductDetailCacheDTO>`；JSON、`__NULL__`、二次读取、token 锁及回填集中在该类。[ProductService](../src/main/java/org/example/takeout/Product/Service/ProductService.java) 的 `loadProductDetailFromMysqlOnly` 对缺失行返回 null，由缓存层回填、业务层在正常/降级路径统一校验 null 与商家归属后转换 VO。普通缓存及空值标记回填失败改为缓存组件内局部捕获，沿用已加载结果，不再触发外层第二次数据库回源；这是行为改变。当前首次缺失与后续空值标记命中的异常文案不同。
- 残留：旧内联缓存实现已删除；商品业务 Service 仍负责查询与回源结果校验，符合实际迁移范围。首次锁失败后重试不是从头持有数据库行锁；不能从缓存锁推断整个业务事务已串行化。
- 测试：`e72686c` 新增 `ProductDetailCacheServiceTest` 并收缩 `ProductServiceTest`；本次补充真实 Supplier、null 回源、锁内二次命中与旧缓存缺失 `inStock` 的重建路径，见 T-P。

**P03：分类查询/锁回到所属模块，分类迁移登记商品缓存失效 — 已完成。**

- 原问题：商品直接读取 `CategoryMapper`；分类删除直接更新 `ProductMapper`，绕过商品缓存失效链路。
- 实际变化：`e72686c` 在 [CategoryService](../src/main/java/org/example/takeout/Category/Service/CategoryService.java) 提供 `getCategory`、`lockActiveCategory`、`getCategoryMap`、`getCategoriesByIds`；新增 [ProductQueryService](../src/main/java/org/example/takeout/Product/Service/ProductQueryService.java) 集中商品有效性查询。分类删除在原外层事务内调用 `ProductCommandService.migrateProductsToCategory`，SQL 锁定源分类所有商品（包含逻辑删除商品）、迁移分类并增加 version，核对更新数量，为每个商品登记缓存失效，再物理删除分类。分类实体没有逻辑删除标记。
- 事务边界：`migrateProductsToCategory` 本身无事务注解，当前唯一生产调用方 `CategoryService.deleteById` 提供外层事务；`lockActiveCategory` 显式按分类 ID、商家与 ACTIVE 状态加 `FOR UPDATE`，不能将其他分类查询也视为自动具有相同范围约束。
- 残留：Category 未全面拆成读写组件；本条目标是恢复数据所有权，不以是否新增 QueryService 判完成。`ProductService` 不再注入 `CategoryMapper`，`CategoryService` 不再直接写 `ProductMapper`。
- 测试：`ProductCategoryMigrationMapperTest` 在 `e72686c` 新增；本次新增 `CategoryServiceTest`，补分类删除委托、锁定范围和失败时不删除等行为，见 T-P。

**P04：商品初始状态迁移与图片清理组件更名 — 已完成（限定这两个子项）。**

- 原问题：Converter 隐含商品初始状态，图片上传技术职责混入商品 Service。
- 实际变化：`e72686c` 的 `ProductService.createProductEntity` 显式设置 `OFF_SALE/NOT_DELETED`，ProductConverter 去除这两个默认状态。分类 ACTIVE 赋值在父版本的 Service 中已存在，本次分类 Converter 变化是 VO 组装迁移，不是分类初始状态迁移。`Product/Service/ProductImageCleanupService` 仅更名为 [ProductImageService](../src/main/java/org/example/takeout/Product/Service/ProductImageService.java)，`Product/Config/ProductImageCleanupTask` 仅更名为 [Product/Config/ProductImageCleanupService](../src/main/java/org/example/takeout/Product/Config/ProductImageCleanupService.java)，调度和清理主体没有完整搬迁。
- 残留：`90af04a` 新增的 `ProductService.uploadImage` 仍处理文件验证、落盘和返回路径，没有上传记录落库逻辑，图片 I/O 未完全抽出；不能把更名记为完整图片 Service 拆分。商品默认状态迁移子项已完成。
- 测试：图片组件测试在 `e72686c` 更名；本次补商品创建状态归属断言，见 T-P。

### CacheInvalidationTask / Common.Redis

**C01：缓存删除从事务内动作改为提交后加持久化补偿 — 已完成。**

- 原问题：`2879400` 虽新增持久化任务，但 `ProductService` 仍在事务内执行 DEL 并把 PENDING 标为成功；提交与缓存删除存在先后窗口。
- 实际变化：关键提交是 **`6e879a7`**（提交标题没有提到该变化）。[CacheInvalidationTaskService.requestInvalidation](../src/main/java/org/example/takeout/CacheInvalidationTask/Service/CacheInvalidationTaskService.java) 使用 `MANDATORY` 在调用者事务里插入任务，首轮重试设为 15 秒之后，注册 `afterCommit` 做立即删除；即时删除后任务仍等待调度器处理。旧公开 `createPending/markPendingTasksSuccess` 入口及商品事务内直接 DEL 已去除。`e72686c` 再由详情缓存组件承担登记入口，并用于商品分类迁移。
- 残留：同一 key 可有多条未完成任务，重试按 `limit 100` 批量扫描；聚合、并发领取及积压治理并未完成。即时删除成功不表示持久化任务已标记 SUCCESS。
- 测试：缓存任务事务集成测试在 `2879400` 新增、`6e879a7/e72686c` 更新，覆盖回滚/提交后行为；实际执行见 T-C。

**C02：通用 Redis 客户端解除 Product 包归属 — 已完成（当前工作区，未提交）。**

- 原问题：缓存任务依赖 Product 包里的通用 Redis 操作，而商品缓存又依赖缓存任务，存在包级互依。
- 实际变化：`e72686c` 仅将 `ProductCacheService` 更名为 `Product.Cache.RedisCacheClient`；`WT-20261011` 才移到 [Common.Redis.RedisCacheClient](../src/main/java/org/example/takeout/Common/Redis/RedisCacheClient.java)，两个生产调用方的 import 已同步。
- 残留：`src/main/src/test` 无旧 Product 包导入；本次将测试文件/package 从 `Product/Cache` 同步搬到 `Common/Redis`。Common 鉴权组件仍依赖账号业务服务，这是另一条边界问题，不代表整个 Common 已完全去业务依赖。
- 测试：`RedisCacheClientTest` 的更名来自 `e72686c`；本次包迁移后用干净构建核对，见 T-C。

### Cart / CartHeader

**CA01：结算准备独立为 CartCheckoutService，跨模块 Mapper 改为业务查询 — 已完成。**

- 原问题：`cartDomainService` 名称不清晰，直接查 Product/Merchant Mapper；`CartService` 也自行穿透这些存储入口。
- 实际变化：`e72686c` 将其更名为 [CartCheckoutService](../src/main/java/org/example/takeout/Cart/Service/CartCheckoutService.java)，`prepareCheckout` 经 `ProductQueryService`、`MerchantQueryService` 读取；[CartService](../src/main/java/org/example/takeout/Cart/Service/CartService.java) 同步迁移。`WT-20261011` 将单商家来源改为 CartHeader，`CartAvailableResult.merchantMap` 收窄为单个 `merchant`，商品仍批量查询、商家改为单 ID 查询。该后续迁移的完整一致性保护仍有残留，见 CA03。
- 残留：购物车展示/结算仍有本场景状态投影；返回持久化 Product/Merchant 的结果对象边界偏宽，但不能据此断言调用方已越权写实体。商家状态/价格快照在提交阶段是否需要重新检查，设计意图无法确认。
- 测试：`e72686c` 更新 Cart/订单测试；本次新增 `CartCheckoutServiceTest` 并补 header 商家来源与商品/营业过滤规则，不代表已验证 item/product/header 三方 ID 一致性，见 T-CA。

**CA02：CartHeader 串行化购物车写入，消费通过 CartCommandService — 已完成。**

- 原问题：旧 `CartService.add` 通过 count 检查其他商家条目，空购物车并发请求可能都读到 0；订单直接消费 Cart Mapper，购物车写入所有权和并发保护不统一。
- 实际变化：`90af04a` 引入 CartHeader 实体、Mapper、Manager 及注册初始化，购物车写入按用户 header 行锁、绑定/解绑商家；消费从删除整行改为 `quantity >= 快照数量` 的条件扣减、version++、归零再删除，后续新增数量/商品保留。`e72686c` 新增 [CartCommandService.consumeCheckedOutItems](../src/main/java/org/example/takeout/Cart/Service/CartCommandService.java)，订单执行器通过该命令消费。`WT-20261011` 为消费入口补 `MANDATORY` 并新增 header 只读 `getByUserId`；解绑结果检查已在 `90af04a` 存在，不是工作区新增。
- 保留契约：实际落单事务内才锁 header；准备阶段可以成为旧快照，条件消费不匹配应回滚订单/库存/明细及相关缓存失效任务，下单本身不创建配送任务。未见从 `OrderService.createOrder` 开始长期持有 header 行锁的实现。
- 残留：历史用户补 header 由 `V20261010` 完成；运行环境是否执行该迁移无法从 Git 确认。实验 Redis Hash 购物车仍存在，见 O03。
- 测试：`90af04a` 新增 `OrderCartHeaderTransactionTest`、改写 Cart 集成测试；本次补命令代理无事务拒绝/加入外层事务的验证，见 T-CA。

**CA03：CartHeader 作为单商家读取来源的后续迁移 — 部分完成（当前工作区，未提交）。**

- 原问题与修改：原结算以各 cart item 的商家构建 Map；`WT-20261011` 改为 header 的单个商家对象，订单准备也直接使用该对象，旧多商家检查被移除。
- 当前残留：[CartService.list](../src/main/java/org/example/takeout/Cart/Service/CartService.java) 对非空购物车直接解引用 header，缺行时可能 NPE；Checkout 缺 header 返回空结果。Checkout 没有再次比较 item/product/header 商家 ID，迁移预检能保护历史脏数据，但不证明所有后续旁路数据一致。营业判定也不完全相同：list/Query 要求 BUSINESS_OPEN，Checkout 仅排除 BUSINESS_CLOSED，null/未知状态的结果可能不同。
- 测试：新增 Checkout/header 来源与关闭商家用例；没有把上述异常边界写成已通过验证或故意支持的契约。是否有意完全依赖 header 与正常枚举值，无法确认，见 D10。

### Order / DeliveryTask

**O01：删除混合职责 OrderDomainService，收回查询/计价并抽摘要 — 已完成。**

- 原问题：`OrderDomainService` 混合数据库查询、计价、编号与商品摘要。
- 实际变化：`e72686c` 删除该类，查询/计价迁回 [OrderService](../src/main/java/org/example/takeout/Order/Service/OrderService.java)，编号生成迁入 [OrderTransactionExecutor](../src/main/java/org/example/takeout/Order/Service/OrderTransactionExecutor.java)，摘要放入 [OrderProductSummaryBuilder](../src/main/java/org/example/takeout/Order/Support/OrderProductSummaryBuilder.java)。`OrderVOBuilder` 更名为 [OrderVOAssembler](../src/main/java/org/example/takeout/Order/Assembler/OrderVOAssembler.java)。
- 残留：旧类及主/测试引用已清除；没有独立 PricingPolicy/NumberGenerator，不能把未要求的类拆分列为已完成或必需迁移。OrderVOAssembler 仍有集合映射简化 TODO，未记为已实施。
- 测试：订单校验、幂等、事务执行器测试在 `e72686c` 改为新依赖；本次执行见 T-O。

**O02：配送写订单改走订单命令，命令/只读断言边界继续收窄 — 已完成（含未提交后续）。**

- 原问题：`DeliveryTaskService` 直接修改 `OrderMapper` 并解释订单状态。
- 实际变化：`e72686c` 经 [OrderCommandService](../src/main/java/org/example/takeout/Order/Service/OrderCommandService.java) 的 `updateOrderStatusToDelivering/updateOrderStatusToDelivered` 推进订单状态，骑手资格委托 RiderService，外层 [DeliveryTaskService](../src/main/java/org/example/takeout/DeliveryTask/Service/DeliveryTaskService.java) 事务保留。**该提交的两个写命令已都接收 `Long orderId`**，不是工作区才收窄送达参数。`WT-20261011` 的实际后续是将重复请求的 `assertDelivering/assertOrderReachedDeliveryCompletion` 只读断言移到 OrderService。
- 事务边界：当前订单状态命令自身没有方法事务注解；单条条件更新原子性不等于跨订单/配送表的事务，后者由 DeliveryTaskService 外层事务承担。
- 残留：生产端无旧订单 Mapper 穿透和 DeliveryTask 参数入口。`OrderService` 自身仍承担多类用例；本条没有声称全模块完成 CQRS。
- 测试：`e72686c` 修改了配送测试，但其 `DeliveryTaskServiceTest` 仍有 `.updateOrderStatusToDelivered(any(DeliveryTask.class))`，与当时业务的 Long 参数不匹配，这是可从源码确认的测试未同步；不能因业务入口完成就记测试闭环。当前用户工作区已修正为订单 ID，并有 `OrderDeliveryStateQueryTest`；本次补命令状态流转覆盖并运行，见 T-O。

**O03：数据库幂等增加请求指纹，删除未接入生产入口的 Redis 订单实验 — 已完成。**

- 原问题：Redis PROCESSING/SUCCEEDED 实验状态与实际数据库订单幂等并存；同 requestId 的不同请求需要明确裁决。
- 实际变化：`90af04a` 删除 `RedisOrderCreationExperiment`、`RedisOrderIdempotencyStore`、`RedisIdempotencyState`，引入 [OrderRequestFingerprint](../src/main/java/org/example/takeout/Order/Support/OrderRequestFingerprint.java) 与 `orders.request_hash`，业务查询/重复键后回读校验姓名、电话、地址、备注四字段的 SHA-256 指纹（不包含购物车明细），新增迁移补齐/确认 `user_id + request_id` 唯一约束；父版本 deploy/schema.sql 已有该唯一键，不能写成本轮首次收紧。删除前 Controller 已直接调用 OrderService，不能记成生产从 Redis 切换到数据库。`28a3edb`（2026-07-29）已经拆出独立事务执行器并有数据库幂等，`1653171` 再将库存副作用从明细快照构建分离、按商品 ID 固定扣/退库存顺序。
- 残留：删除的 Redis 订单实验类型无源码引用；[RedisHashCartServiceExperiment](../src/main/java/org/example/takeout/Cart/Service/RedisHashCartServiceExperiment.java) 及其单元/真实 Redis 集成测试仍保留，源码明确标注实验、不参与实际业务。是否计划删除该购物车实验无法确认，不能记为已清理。
- 其他实验：[RedisSetFollowServiceExperiment](../src/main/java/org/example/takeout/Cart/Service/RedisSetFollowServiceExperiment.java) 也仍在 main 中，不属于此次已删除的订单 Redis 实验类型。
- 测试：原 `RedisServiceIntegrationTest` 在 `90af04a` 删除；指纹测试、订单幂等单元/事务/集成测试新增或更新。测试替代范围不等于与旧套件逐用例等价，等价性无法确认。执行见 T-O。

**O04：订单项查询与图片快照兜底收回 OrderItemService — 已完成（当前工作区，未提交）。**

- 原问题：商家视图组件直接批量读取/分组 OrderItem，订单项图片空值处理不集中。
- 实际变化：`WT-20261011` 的 [OrderItemService](../src/main/java/org/example/takeout/Order/Service/OrderItemService.java) 承担订单 ID 到明细的映射，以及订单项创建的默认图片兜底；商家视图改调用这些业务方法。
- 残留：旧商家视图 OrderItemMapper 依赖已清除；具体查询分组、空订单/明细异常的契约由测试核实。此变更没有历史 commit，不能归到 `e72686c`。
- 测试：本次同步 OrderItem/商家视图测试，见 T-O、T-M。

**O05：超时取消失败退避与扫描字段迁移 — 已完成。**

- 原问题：仅按 `create_time` 扫描，取消持续失败的老订单会重复占据一批有限扫描名额。
- 实际变化：`e72686c` 新增 `Order.timeoutCancelAvailableAt`，事务执行器按 `createTime.plusMinutes(30)` 初始化该时间，`OrderMapper.selectTimeoutOrderIds` 改按它排序/筛选；[OrderTimeoutTask.cancelTimeoutOrders](../src/main/java/org/example/takeout/Order/Config/OrderTimeoutTask.java) 逐单失败时调用 `delayTimeoutCancellation`，按仍 WAIT_PAY 的条件延迟 30 分钟，继续处理后续订单。`V20261008` 回填及增加相应索引。
- 残留：回滚时是否影响库存由原取消事务保护；调度器单元测试不替代真实 MySQL SQL 验证。旧按 create_time 的索引在迁移历史中保留，不能宣称已清理所有旧索引。
- 测试：`OrderTimeoutTaskTest` 在 `e72686c` 更新，`OrderTimeoutCancellationTest` 和订单集成测试覆盖状态竞争/退库存，见 T-O、T-DB。

### Merchant / User / Rider / Common.Auth

**M01：店铺查询从 MerchantQueryService 分离 — 已完成。**

- 原问题：原商家查询同时承担商家基础查询和顾客店铺商品/分类展示。
- 实际变化：`e72686c` 新增 [CustomerShopQueryService](../src/main/java/org/example/takeout/Merchant/Service/CustomerShopQueryService.java)，顾客店铺 Controller 转向它，商品/分类通过所属模块 Service 查询；[MerchantQueryService](../src/main/java/org/example/takeout/Merchant/Service/MerchantQueryService.java) 保留轻量商家查询。`WT-20261011` 将其 `checkMerchantOpen` 缺失/打烊判定修正为“存在且营业”，Cart 调用方语义同步调整。
- 残留：该 QueryService 当前保留，源码依赖链表明 CartCheckout 若改依赖 MerchantService，会经 DeliveryTask、OrderService 回到 CartCheckout；当前未使用 `@Lazy` 绕过该环。无法从 Git 恢复一度合并再回滚的中间方案，不能记为提交过的重构。
- 测试：原 MerchantQuery 测试在 `e72686c` 更名为 CustomerShopQuery 测试；工作区另有基础 MerchantQuery 测试。本次补批量分类读取，见 T-M。

**M02：商家订单列表改为视图组件，写分类由分类命令承接 — 部分完成（仓库迁移已完成，外部接口适配无法确认）。**

- 原问题：商家直接写默认分类，订单列表组件带订单 Mapper 与明细存储规则。
- 实际变化：`e72686c` 新增 [CategoryCommandService.createDefaultCategory](../src/main/java/org/example/takeout/Category/Service/CategoryCommandService.java)，Merchant 注册通过该命令初始化，命令自身无事务注解，注册外层事务保留。`WT-20261011` 把 `MerchantOrderQueryService` 更名为 [MerchantOrderViewService](../src/main/java/org/example/takeout/Merchant/Service/MerchantOrderViewService.java)，状态过滤/订单查询归 OrderService，分页的 `PageHelper.startPage` 仍在视图组件，明细查询归 OrderItemService，Controller/列表类型同步。
- 接口迁移：列表类型从 `PENDING/ACCEPTED` 改为 `PENDING/PROCESSING/HISTORY`；PROCESSING 对应 PREPARING，HISTORY 对应 READY/DELIVERING/DELIVERED/FINISHED，表示商家环节结束，不是全订单履约终态。仓库旧引用已清除，外部客户端是否同步无法确认。
- 残留：旧列表组件及其生产引用已去除；Common 拦截器仍依赖 MerchantService。默认分类命令只是已有独立能力，不代表 Category 全部命令已迁移。
- 测试：商家注册测试在 `e72686c` 更新；视图测试工作区已有更名，本次补异常明细/历史订单过滤验证，见 T-M。

**A01：唯一手机号校验由 HTTP Validator 回到账号业务层 — 已完成。**

- 原问题：`UniquePhoneValidator` 通过 JdbcTemplate 按注解 targetTable 拼 SQL 查询账号表，业务校验绑在参数验证；绕过 HTTP 调 Service 时不一定执行。
- 实际变化：`e72686c` 删除 `UniquePhone`、`UniquePhoneValidator` 及三个注册 DTO 注解，User/Merchant/Rider Service 注册时进行本模块手机号预检查。Merchant/Rider 注册捕获 `DuplicateKeyException` 转业务异常，User 注册并发重复键仍由 GlobalExceptionHandle 转通用 DATABASE_ERROR。Merchant 更新预检查排除当前账号早在 `638ff41` 已存在，不属于本轮新增。
- 残留：旧注解/Validator 无主/测试引用；预检查不能独自防并发重复，最终裁决仍由数据库约束承担。
- 测试：Merchant/Rider Service 测试在 `e72686c` 更新，但 UserServiceTest 当时未同步注册路径；本次补 CartHeaderManager mock、User 注册与商家更新排除自身等，并移除 MockMvc 基类遗留的手机号 Validator JdbcTemplate mock/stub，见 T-A。

**A02：账号实时资格判断回到账号模块，JWT 接入商家/骑手状态 — 已完成（当前工作区，未提交）。**

- 原问题：身份令牌有效不能证明账号当前仍可用；业务资格应由账号模块解释。
- 实际变化：USER 实时检查来自更早的 `638ff41`；`e72686c` 新增 `RiderService.requireActiveRiderId` 但当时抛 `BusinessException`。`WT-20261011` 改为 `AuthException`、新增 `MerchantService.requireActiveMerchantId`，并在 [JwtInterceptor](../src/main/java/org/example/takeout/Common/Interceptor/JwtInterceptor.java) 的 MERCHANT/RIDER 分支调用，先设置对应身份上下文再复核账号，异常时清理全部上下文。商家资格只确认账号存在，允许打烊账号管理店铺；没有商家封禁状态支持的证据。
- 残留：拦截器仍位于 Common 且依赖三类账号 Service，整体共享边界整理属部分完成。`JWTUtils.createToken(Long id, String role)` 从 `a775d63` 引入时已经显式传 ID，本轮没有该接口迁移；不能重复记功。
- 测试：工作区已有 JwtInterceptor/Merchant/Rider 资格测试，本次执行见 T-A。

**A03：共享登录失败限流和订单创建限流 — 已完成。**

- 原问题：登录失败计数/Redis 故障语义未集中；创建订单无本次新增的滑动窗口保护。
- 实际变化：`2879400` 新增 [LoginAttemptLimiter](../src/main/java/org/example/takeout/Common/Auth/LoginAttemptLimiter.java) 与 [RedisLoginAttemptStore](../src/main/java/org/example/takeout/Common/Redis/RedisLoginAttemptStore.java)，三个账号 Service 共用失败计数/清除；`6e879a7` 新增 [OrderCreateRateLimiter](../src/main/java/org/example/takeout/Order/Limited/OrderCreateRateLimiter.java) 的 Lua 滑动窗口（10 秒 5 次），同时 `BusinessException` 保存 ResultCodeEnum、全局异常处理返回真实枚举码。源码对 Redis 故障明确选择 fail-open。
- 残留：限流不替代订单数据库幂等；登录限流异常与通用业务异常的响应形态仍按现有全局处理器区分。
- 测试：两个限流器单元、Redis 集成和响应测试在对应提交新增/更新，见 T-A、T-O。

### 数据库迁移与部署

**DB01：手工 SQL 升级迁入 Flyway，增加累计基线及历史数据修复 — 已完成（源码）；环境执行无法确认。**

- 原问题：修改 `deploy/schema.sql` 不会升级已有库，手工增量 SQL 与运行实体结构缺少统一版本记录。
- 实际变化：`90af04a` 增加 flyway-core/mysql 依赖、[FlywayConfig](../src/main/java/org/example/takeout/Config/FlywayConfig.java)、classpath V 迁移及应用配置。`e72686c` 添加 `B20261008` 累计基线与 V20261007–11，部署目录统一为同名镜像并删除旧手工命名脚本。[迁移目录说明](../deploy/migrations/README.md) 明确 classpath 为权威来源。
- 迁移内容：V07 调整用户名称排序规则/订单扫描索引；V08 超时取消退避；V09 补配送奖励快照及 Rider/DeliveryTask 自增 ID；V10 预检异常购物车并补 header、修正商家绑定；V11 预检空/孤儿分类，再对齐 product/merchant/cache task 默认值与非空约束。历史数据预检用临时 CHECK 表，已有奖励/时间字段有保留逻辑，不能当成一次普通表重建。
- 残留：两个目录共 12 个 SQL 的名称集合和文件哈希已逐一核对一致，这是静态证据。实际部署的 `flyway_schema_history` 未读取，无法确认生产/开发库已升级。MySQL DDL 不应仅凭 Java `@Transactional` 判定整体可回滚。
- 测试：`e72686c` 新增 `FlywayMigrationIntegrationTest`，默认 opt-in；普通 `mvn test` 不会执行该类的迁移用例。实际启用结果见 T-DB。

## 3. 重要架构决策及依据

| 决策/契约 | 可确认依据 | 确认边界 |
| --- | --- | --- |
| 按完整职责拆 Service，保留清晰的 Service 间调用 | `e72686c` 已提交架构报告；ProductCommand/DetailCache、CustomerShopQuery 的实际迁移 | 不能推断已采用完整 DDD、微服务或全量 CQRS |
| 模块写入归数据所有者，应用事务协调跨模块用例 | `e72686c` 的 CartCommand、CategoryCommand、OrderCommand 调用；原外层事务保留 | 接口收口不等于所有跨模块实体已经 DTO 化 |
| 数据库持久化幂等是落单裁决来源 | `28a3edb` 事务执行器、`90af04a` 唯一约束/指纹；Redis 订单实验删除前也未接入生产入口 | Redis 创建限流独立存在，不能据此断言“不再用 Redis”或“本轮生产切换数据库” |
| 缓存是提交后最终一致，失效意图参与业务事务 | `6e879a7` 的 MANDATORY + afterCommit + 延迟 PENDING；当前缓存任务代码 | 不保证零缓存陈旧窗口，也未实现任务聚合 |
| 库存条件更新、固定顺序、失败回滚继续保留 | `1653171/90af04a/e72686c` 的 SQL、商品 ID 排序与事务执行器 | Mock 验证调用顺序不能证明真实数据库并发行为 |
| 商家基础查询组件提供较窄的依赖边界 | 当前 CartCheckout -> MerchantQuery，MerchantService -> DeliveryTask -> OrderService -> CartCheckout 的实际依赖 | 不把未提交报告描述的中间 @Lazy 方案认作 Git 历史事实 |
| 迁移脚本使用新版本修复历史结构，部署目录只作镜像 | `e72686c` 的 B/V 脚本、SQL 注释和目录 README | 历史环境是否曾执行旧校验和版本无法确认 |

无法确认的设计意图：商家营业状态、商品价格/名称在准备与写入之间的快照容忍范围；保留 Redis Hash 购物车实验的长期计划；是否打算完全拆走图片上传；历次提交运行测试的真实结果。上述项目不能靠类名、TODO 被删或提交标题补全。

## 4. 尚未完成的迁移与技术债

### 4.1 首次回溯时的记录（09:44 运行快照）

以下保留首次结论，便于追溯状态变化；不再将其当作后续修改后的当前清单。

| 编号 | 状态 | 当前证据与后续边界 |
| --- | --- | --- |
| D01 | 部分完成 | P04：图片清理能力已有边界并更名，但 ProductService.uploadImage 的文件验证和写盘仍在原类 |
| D02 | 待处理 | P01：ProductCommandService.decreaseStock 仍在业务实现层组织动态 setSql，参数化 Mapper 下沉未做 |
| D03 | 待处理 | C01：缓存任务按 key 聚合、积压治理/并发领取未完成；现有补偿可以工作，但不能声称具有这些能力 |
| D04 | 部分完成 | A02：资格判断已委托账号 Service，Common 的业务依赖/拦截器归属仍未进一步整理 |
| D05 | 无法确认 | CA01：提交时是否重验营业状态/商品价格的业务快照要求没有足够 Git 证据，需明确需求后再改 |
| D06 | 待处理 | CartAvailableResult/OrderDataContext 仍携带 Product/Merchant 持久化实体；边界收窄尚未完成，未据此认定存在越权写入 |
| D07 | 无法确认 | O03：RedisHashCartServiceExperiment 仍注册为 Service 并有测试，源码称实验；无删除计划的可验证证据 |
| D08 | 待处理 | 当前提交仍有 MapStruct 未映射属性编译警告，需要按字段是否由 Service/Assembler 显式设置逐项判断；编译成功不会证明映射完整 |
| D09 | 无法确认 | DB01：实际部署库结构/已执行迁移/历史校验和未核查；本文验证仅针对测试环境 |
| D10 | 待处理 | CA03：非空购物车缺 header 的 list NPE、item/product/header 商家一致性缺少显式复核、list/Checkout 对异常营业状态结果不一致 |
| D11 | 无法确认 | M02：外部调用方是否同步 ACCEPTED 到 PROCESSING/HISTORY；需对接验证后才能确认整个接口迁移完成 |
| D12 | 待处理 | A01：User 注册、Merchant 更新的并发重复键仍返回通用数据库错误，与 Merchant/Rider 注册的 Service 业务异常转换不同 |
| D13 | 待处理 | IdempotencyDesign.md 的 3.2/3.3 仍描述删除购物车整行并核对行数，与当前按快照数量消费不一致；此处保留为文档残留，不以旧说明覆盖代码 |

### 4.2 同日早期续审时的状态（历史快照）

以下依据当时源码逐项复核，HEAD 未变化。新增实现属于后续工作区，无对应 commit hash；D04 是依据既有分层约定修正定性，并非当时新增代码。旧报告已由[当前项目风险与架构复核](../当前项目风险与架构复核_2026-10-11.md)替代；表内 D05、D08、D10 等历史结论已重新定性，不能作为当前待办。

| 编号 | 当前状态 | 相对首次记录的变化 / 当前证据 |
| --- | --- | --- |
| D01 | 部分完成 | uploadImage 仍在 ProductService，清理组件已经独立；既有报告允许当前单一用例暂留上传，作为可选职责整理，不记作已迁移 |
| D02 | 已完成（源码） | ProductCommandService.decreaseStock 现调用 ProductMapper.decreaseStock；Mapper 用参数绑定保留原子库存、状态、version 更新，Product 源码无 setSql。10:02 的测试同步已存在，当前版本尚无运行证据 |
| D03 | 待处理 | 每次失效登记仍 INSERT，重试仅 limit 100；同 key 聚合、执行前领取、积压和成功任务清理治理未实现 |
| D04 | 已完成（限定账号资格归属） | 三类账号资格均由所属 Service 解释；统一 JWT/角色路由/上下文清理留在 Common 符合既有约定，不把合理 Service 依赖单独认作未完成分层迁移。Context 目录命名仅是可选整理 |
| D05 | 无法确认 | 写入阶段有库存/在售/未删除条件复核，但价格/名称仍用准备快照，营业/价格重验要求缺少设计依据 |
| D06 | 部分完成 | CartAvailableResult、OrderDataContext 及其消费链已改为 merchantId/merchantName；仍复用 CartItem/Product 快照，未发现由此越权写入，不强制 DTO 化 |
| D07 | 无法确认 | RedisHashCartServiceExperiment 仍是 Service 并有测试，未见生产入口调用，长期去留计划无法确认 |
| D08 | 待处理 | Converter 告警仍需区分后置赋值与缺值；ProductVO.categoryName 当前转换链未补齐，是否需要该字段仍须核对接口契约 |
| D09 | 无法确认 | 续审重新比对 12 份迁移 SQL 镜像一致；未读取实际部署库迁移历史，测试环境通过不能替代部署验证 |
| D10 | 待处理 | CartService.list 缺 Header 仍直接解引用；Checkout 缺 Header 返回空结果，未核对 item/product/header 商家 ID；null/未知营业状态与 list 结果仍不同。新增 merchantId 空值分支不代表整体修复 |
| D11 | 无法确认 | 代码入口仅含 PENDING/PROCESSING/HISTORY，未见 ACCEPTED 兼容映射；仓库外客户端迁移尚未核查 |
| D12 | 已完成（服务源码） | UserService.register 预查用户名/手机号后捕获 userMapper.insert 的 DuplicateKeyException，并返回 BUSINESS_ERROR“用户名或手机号已经存在”；MerchantService.updateMerchant 预查排除自身的手机号后捕获 updateById 异常，并返回 BUSINESS_ERROR“手机号已被其他商家使用”。数据库唯一约束仍是最终并发裁决；全局 DATABASE_ERROR 为其他未捕获入口兜底。测试源码未覆盖这两个新分支，见第 5.4 节 |
| D13 | 已完成（文档，2026-10-11） | `IdempotencyDesign.md` 3.2–3.4 已改为当前执行链：结算快照数量条件减量、归零删除、Header 锁/解绑、订单/购物车/库存/明细同事务回滚，并区分相同与不同 `requestId` 的并发语义；未改代码、未运行测试 |

新发现的测试证据缺口记为检查报告 V01：User 登录改为 UNAUTHORIZED/FORBIDDEN 后，Service 测试仅验证异常类型，API 测试仍 mock BUSINESS_ERROR；详见第 5.4 节。此处不以没有运行证据推断实现失败。

## 5. 测试更新与验证状态

### 5.1 对应测试与本次适配

测试路径均相对于 `src/test/java/org/example/takeout/`。本次新增/补充围绕迁移后的公开职责和失败语义，未通过删除断言、扩大 lenient 或跳过失败用例来适配。

| 索引 | 记录 | 测试与历史更新 | 本次变化/执行范围 |
| --- | --- | --- | --- |
| T-P | P01–04 | Product/Service 下 Command、Query、DetailCache、ProductService 测试；Product/Mapper/ProductCategoryMigrationMapperTest；图片服务测试。主要更新于 e72686c | 迁移 Redis 测试包、补 Supplier/null/锁内命中/旧缓存重建/创建状态；新增 Category/Service/CategoryServiceTest |
| T-C | C01–02 | CacheInvalidationTask/Service/CacheInvalidationTaskServiceIntegrationTest（2879400、6e879a7、e72686c）；RedisCacheClientTest（原 ProductCacheServiceTest） | 保留工作区 Common import；测试文件/package 移至 Common/Redis；使用干净构建避免旧包 class 冒充新用例 |
| T-CA | CA01–03 | CartHeaderServiceRegressionTest、CartConsumptionMapperTest、OrderCartHeaderTransactionTest、CartServiceIntegrationTest（90af04a/e72686c） | 新增 CartCheckoutServiceTest；补事务代理 MANDATORY 与 header 来源验证；不把 H2 中 mock 的库存/明细视为真实库存回滚验证 |
| T-O | O01–05 | OrderItem/Command/Validation/Idempotency/TransactionExecutor/RequestFingerprint/Timeout 测试；DeliveryTaskServiceTest 与订单/配送集成 | 同步订单项查询入口、图片兜底、订单状态断言与命令参数；新增 OrderMerchantQueryTest 检查商家/状态 SQL Wrapper；不把只读断言测试当作写入事务验证 |
| T-M | M01–02 | CustomerShopQueryServiceTest、MerchantServiceTest（e72686c）；工作区 MerchantQuery/OrderView 测试 | 补分类批量读取、历史列表委托、异常明细等契约；视图 mock 测试不证明 SQL 状态过滤 |
| T-A | A01–03 | User/Merchant/Rider Service、JwtInterceptor、LoginAttemptLimiter 与 RedisLoginAttemptStore 集成测试 | 保留工作区资格/异常变化；补注册手机号预检查、header 初始化、更新手机号排除当前账号。Merchant 注册有并发 DuplicateKeyException 测试；User 注册插入竞态、Merchant 更新竞态的新增捕获分支没有直接测试，当前版本未运行 |
| T-DB | DB01、O05 | integration/FlywayMigrationIntegrationTest（e72686c，默认禁用） | 需单独启用；用例创建随机 takeout_migration_test_* 数据库，验证升级/空库/数据保留/预检/演示数据重复导入 |

### 5.2 首次回溯实际执行（09:44 快照）

**该次运行结果：2026-10-11 09:44:34 +08:00 完成，57 个套件，655 个测试，Failures=0、Errors=0、Skipped=0，BUILD SUCCESS，退出码 0。** 根据该次 `clean` 后生成的 57 份 Surefire XML 逐项汇总，未使用旧报告推断结果。Java 为 Microsoft OpenJDK 17.0.18，Maven 为 3.9.15。基准为运行时的 HEAD 加未提交工作区，没有创建提交；该结果不覆盖第 5.4 节的后续源码修改。

实际最终命令（项目根目录）：

```powershell
mvn -B -ntp clean test "-Dtakeout.migration-test.enabled=true" "-Dspring.data.redis.host=172.30.185.119" "-Dspring.data.redis.port=16379" "-Dspring.data.redis.password=architecture-test-20261011"
```

环境：普通 Spring 测试使用 H2、关闭应用启动 Flyway；MySQL 集成测试使用源码配置的专用 `localhost:3306/takeout_integration_test`。迁移用例显式启用，在随机命名的 `takeout_migration_test_*` 库中执行。Windows 不能连接原 `127.0.0.1:6379`，本次使用已有 WSL Redis 7.0.15 创建独立 16379 端口、认证、无持久化的临时实例；Windows 侧 AUTH/PING 成功后执行测试，结束后关闭。上面的地址和临时测试密码只对应本次运行；以后替换成可连接的测试 Redis 配置，不需改业务源码/测试 YAML。

| 证据范围 | 本次实际执行的关键套件（括号为测试数） | 结果与证明边界 |
| --- | --- | --- |
| T-P | CategoryServiceTest(5)、ProductServiceTest(16)、ProductDetailCacheServiceTest(11)、ProductCommandServiceTest(9)、ProductQueryServiceTest(1)、ProductCategoryMigrationMapperTest(2)，以及图片套件 | 全部通过；Mockito 编排、缓存故障分支与 H2 Mapper 行为分别验证，不把 mock 当作数据库锁 |
| T-C | CacheInvalidationTaskServiceIntegrationTest(15)、Common.Redis.RedisCacheClientTest(6) | 全部通过；前者真实 Spring/H2 事务、Redis mock；后者客户端单测。旧 Product/Cache 测试 class 在 clean 后不存在 |
| T-CA | CartCheckoutServiceTest(3)、CartHeaderServiceRegressionTest(8)、CartConsumptionMapperTest(12)、OrderCartHeaderTransactionTest(8) | 全部通过；结算只读过滤、header 并发/回滚、条件消费、事务代理拒绝无外事务。后者库存/明细组件被 mock，完整事务另见 MySQL 套件 |
| T-O | OrderItemServiceTest(10)、OrderMerchantQueryTest(4)、OrderServiceValidationTest(4)、OrderCommandServiceTest(11)、OrderDeliveryStateQueryTest(20)，以及幂等/指纹/超时套件 | 全部通过；查询 Wrapper/明细兜底/命令与只读断言分别验证。Wrapper 断言不等于在 MySQL 执行过全部商家列表 SQL |
| T-M | CustomerShopQueryServiceTest(3)、MerchantOrderViewServiceTest(8)、MerchantQueryServiceTest(6)、MerchantServiceTest(15)、MerchantFulfillmentServiceTest(7) | 全部通过；视图层证据限定委托/组装/失败传播，外部客户端迁移未验证 |
| T-A | UserServiceTest(13)、RiderServiceTest(14)、JwtInterceptorTest(8)、LoginAttemptLimiterTest(5)，以及账号 API 套件 | 全部通过；真实账号 Service 单测与 mock 业务的 HTTP 契约测试不同，不能推出生产账号数据状态 |
| 真实 MySQL | CartServiceIntegrationTest(6)、CategoryProductServiceIntegrationTest(107)、DeliveryTaskLifecycleIntegrationTest(6)、MysqlApiIntegrationTest(22)、OptimisticLockIntegrationTest(5)、OrderServiceIntegrationTest(28) | 全部通过、无跳过；覆盖现有购物车、分类/商品、履约、库存/订单事务及并发场景，仍不代表所有交错或生产压力验证 |
| 真实 Redis | RedisHashCartServiceExperimentIntegrationTest(27)、OrderCreateRateLimiterIntegrationTest(1)、ProductDetailCacheInvalidationIntegrationTest(1)、RedisBasicIntegrationTest(4)、RedisLoginAttemptStoreIntegrationTest(3) | 全部实际执行并通过、无 assumption 跳过；使用本次临时 Redis，不代表生产实例状态 |
| T-DB | FlywayMigrationIntegrationTest(9) | 全部实际执行并通过；覆盖旧库升级、空库初始化、历史值保留、购物车/分类预检和演示数据重复导入 |

本次过程中的失败也保留其范围：第一次完整运行是 652 个测试，Errors=28、Skipped=15、Failures=0；27 个错误来自 Redis 连接失败，1 个是新增 `OrderServiceValidationTest` 的 Mockito matcher 用法错误，已先独立计算预期指纹再进行 verify。15 个跳过来自默认禁用的 Flyway 及 Redis assumption。最终重新 `clean`、启用迁移并提供可连接 Redis 后得到上面的 655/0/0/0；参数化迁移用例启用后展开，因此总数与禁用时不同。早先沙箱启动阻塞以及未完成的中间运行没有完整结果，不作为通过证据。

当时的完整测试和排查日志现已清理，本文保留其关键统计、命令和环境；`target/surefire-reports/` 属于可被下一次构建替换的运行产物，不能用来重新证明历史版本。静态校验当时另确认：旧类型/旧包引用无匹配，迁移镜像 12 个文件逐一哈希相同，34 个文档文件链接均存在，`git diff --check` 无空白错误。这些历史静态结果与运行通过分开记录；本轮当前证据见统一风险报告。

默认配置依旧不会自动提供所有外部依赖：部分 Redis 集成在不可用时通过 assumption 跳过，Redis Hash 实验集成则直接失败；Flyway 用例仍需显式启用。以后运行普通 `mvn test` 应重新检查跳过数，不能借用本次结论。

### 5.3 历史结果

上述历史 hash 可确认测试文件的新增、修改、搬迁与删除。仓库没有与每个提交绑定的完整测试执行记录；历史报告引用的局部 Bean 图诊断与静态检查只证明其限定范围。**历史各版本全套测试是否执行并通过：无法确认。** 第 5.2 节仅适用于 09:44 运行时的工作区快照。

### 5.4 同日后续代码变化与验证缺口

当前日志完成时间为 09:44:34；现存 Surefire 报告和编译产物也早于以下源码修改。时间来自文件修改时间，仅用于证明验证先后顺序，**不是 Git 提交时间或设计意图的证据**。

| 时间 / 复核记录（+08:00） | 当前代码 / 测试变化 | 尚未验证的内容 |
| --- | --- | --- |
| 09:51 | UserService.login 错误凭据改为 UNAUTHORIZED，不可用账号改为 FORBIDDEN | UserServiceTest 的相关用例只断言 BusinessException 类型；AuthAndAccountApiTest 的错误凭据用例仍 mock/期待 BUSINESS_ERROR，不能证明真实 Service 的新响应码。检查报告 V01 保留此缺口 |
| 10:02 | ProductMapper 新增参数化 decreaseStock；ProductCommandService 委托该方法，ProductCommandServiceTest 已同步 mock/verify | 当前源码尚未重新编译或运行；mock 入口验证不能替代新 SQL 的真实 MySQL 库存、状态和回滚验证 |
| 10:07 | CartAvailableResult / OrderDataContext 收窄 Merchant 为 id/name；Checkout、OrderService、执行器及相关测试同步 | 当前下单链路尚未重新运行；CartItem/Product 仍复用，Header 异常及商家一致性残留未闭环 |
| 2026-10-11 本次复核 | UserService.register 预查用户名/手机号后，对 userMapper.insert 的 DuplicateKeyException 转 BUSINESS_ERROR；MerchantService.updateMerchant 排除自身预查手机号后，对 updateById 的 DuplicateKeyException 转手机号冲突的 BUSINESS_ERROR。数据库唯一约束承担并发裁决；全局 DATABASE_ERROR 仍为未捕获入口兜底 | UserServiceTest 只覆盖注册前置查重；MerchantServiceTest 覆盖商家注册并发重复但未覆盖商家更新并发；这两个新增捕获分支没有直接测试，本轮未运行测试 |

本次 D12 续审只更新两份文档，静态核对服务源码、测试源码和全局异常兜底；没有修改业务或测试文件，也没有重跑 Maven。本次不能把“测试源码存在”写成“测试已执行通过”，也不能将历史 655/0/0/0 外推为当前全部通过。

## 6. Git 提交索引

| 时间（+08:00） | 完整 hash | 经实际差异确认的关联记录 |
| --- | --- | --- |
| 2026-08-28 18:31:08 | `16531712582b34fa1d6a26bf19fbba3dfaf4f42c` | P01 前置库存售罄/退库/状态规则与并发边界 |
| 2026-09-08 19:49:27 | `434a0af75dea30b01575952c6c6d8174f42a524a` | P02 前置商品缓存 Redis 门、锁/降级等 |
| 2026-09-12 21:01:05 | `28794005512c447c295f3d8b5dedb71515862c5c` | C01 持久化缓存任务初版；A03 共享登录失败限流 |
| 2026-09-29 21:48:50 | `6e879a7a37f339cbe65d800acb9493bf1e404049` | C01 提交后删除关键契约；A03 创建限流/异常码/构造器注入；商品有效名称唯一约束 |
| 2026-10-06 20:54:18 | `90af04adc7c74a63ad326972897df9b5b4640b22` | CA02 header；O03 指纹、唯一键、Redis 订单实验删除；DB01 Flyway 初接入 |
| 2026-10-09 21:28:16 | `e72686c98b90ce2283d0881e085abe2233506c77` | P01–04、CA01–02、O01–02/O05、M01–02、A01；C02 仅类更名；DB01 基线及后续修复 |
| 本次开始时的工作区 | 无 hash，`WT-20261011` | C02 Common 迁包、CA01–03 header/事务强化、O02 只读断言迁移及旧测试参数适配、O04 订单项查询、M01 营业语义/M02 视图更名、A02 商家/骑手资格 |
| 本次测试与文档适配 | 无 hash，未自动提交 | 第 5 节新增/更新测试与本文；提交后补充实际 hash，不使用 HEAD 代替 |
| 同日后续工作区修改 | 无 hash，未提交 | 09:51 User 登录错误码；10:02 D02 参数化扣库存；10:07 D06 Merchant 标量化及相关测试同步。日期与验证边界见第 5.4 节 |
| 同日检查报告续审 | 无 hash，未自动提交 | 仅更新两份文档；保留首次快照，新增 D01–D13 当前状态与 V01 测试缺口，未新增运行结果 |
| 本次 D13 文档修订 | 无 hash，未自动提交 | IdempotencyDesign.md 3.2–3.4 按当前购物车数量条件消费和订单事务执行链更新；同步当前 D13 状态。未改业务/测试代码，未运行测试 |
| 本次 D12 文档续审 | 无 hash，未自动提交 | 根据当前工作区 UserService.register 与 MerchantService.updateMerchant 的 DuplicateKeyException 转换，更新 D12 当前结论和测试覆盖缺口；仅改两份文档，未运行测试 |

更早来源仅用于排除错误归因：`a775d63ff796de15bc106f649c22650748a2bbc5`（2026-06-09）引入 JWTUtils 且已显式接收账号 ID；`28a3edb49e5ae274e8d9e76a9e9e0eb40b7d4b81`（2026-07-29）拆订单事务执行器并建立数据库幂等；`638ff4177becc89fcd85a411edc3556e95913f03`（2026-08-18）增加 USER 实时资格检查，并将商家接单/出餐的订单命令委托至 OrderCommandService。它们不计入本轮首次新增量。

## 7. 后续维护方式

1. 保留 P/C/CA/O/M/A/DB/D 编号。新重构新增记录；原结论变化时写明新日期、hash、实际入口及验证变化，不删除原有历史。
2. 每次修改核对 `git show <hash>^:<path>` 与 `git show <hash>:<path>`，跨重命名同时检查旧路径和新路径；工作区改动提交后补实际 hash。
3. 对每条更新同时记录原问题、实际变化、类/方法、旧入口/残留、实现状态、测试变化与运行结果。目标缩小时明确记录限定范围，不用一个“已完成”替代测试证据。
4. 测试记录写执行日期、基准 hash/未提交状态、命令、Tests/Failures/Errors/Skipped，以及环境与跳过原因。新增/编译/静态检查/运行通过必须分别记录。
5. Git 无法证明的设计意图继续标为「无法确认」；待需求或新的设计记录补齐后再更新，不按架构惯例推测。
