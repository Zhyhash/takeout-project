# Stripe 支付链路重构交付报告

日期：2026-11-05。范围：现有 Stripe 测试支付、订单支付资格与取消、退款遗留、相关测试和支付文档。没有 Git commit，没有启用真实支付，没有实现退款。

> **2026-11-06 修订**：报告中的跨实例租约机制已在同日简化。移除 `lease_token`、`lease_until`、`idempotency_expires_at`、`last_error` 四列；占位改由 `claimAttempt` 前推 `next_retry_at` 完成，幂等有效期由 `create_time + 23h` 推导，诊断信息改由日志承载。同时把网关异常拆成「Stripe 明确拒绝」与「结果未知」两类。下文涉及租约的段落已按当前实现改写，迁移细节见 [数据库迁移说明](database-migrations.md)。

本报告以任务开始时的工作区为审查基准。开始时已有七个 Stripe 相关文件的未提交修改及 `.serena/`；保留这些工作，没有回退或清理。`StripeWebhookController` 的非 2xx 异常保护、Gateway 的测试环境校验和网络超时等既有修改继续保留。

## 1. 修改前调用链与实际问题

创建路径：`StripePaymentService.createOrReuseOrderPaymentIntent` → `StripePaymentTransactionService.getOrCreateOrderPaymentRecord` → Gateway create → `bindPaymentIntentToOrder` → 返回客户端密钥。

- `Payment/Service/StripePaymentService.java#createOrReuseOrderPaymentIntent`：仅检查 WAIT_PAY 并建立支付记录，没有先持久化 PAYING；请求 Stripe 期间超时任务仍能取消订单、归还库存。
- `Payment/Service/StripePaymentTransactionService.java#getOrCreateOrderPaymentRecord`：已有稳定幂等键，但没有把订单支付资格与记录创建放在同一事务中。未实现恢复扫描、任务占位、退避及幂等窗口上限。
- 同文件 `bindPaymentIntentToOrder`：只关联和保存原生状态，首次 create 返回 succeeded 也没有执行订单确认，与 retrieve/Webhook 的规则不一致。
- 同文件 `syncPaymentStatusFromSnapshot` → `updatePaymentStatusAndConfirmOrderIfSucceeded` → `OrderService.confirmStripePayment`：后者在取得 Stripe succeeded 后才连续执行 WAIT_PAY → PAYING → PAID，PAYING 没有承载真实业务等待。前者忽略 boolean 结果，订单已取消或超过期限时仍可提交 succeeded 与事件去重记录，后续同事件永久跳过。
- `Order/Service/OrderService.java#confirmStripePayment`：用发起支付的 30 分钟资格再次拒绝已经成功的支付；无法正确处理已经持久化的 PAYING 和迟到成功。
- `Order/Service/OrderCommandService.java#rejectOrderByMerchant`、`#refunding`：固定返回 true 的模拟退款被当作真实退款，能取消已付订单并退库。
- 编排层原有 NOT_SUPPORTED 只能挂起外层事务，外层持有的锁仍可能存在；不能据此保证 HTTP 期间没有悬挂的本地事务/行锁。
- 用户取消原本只允许 WAIT_PAY；原超时取消也是 WAIT_PAY 条件更新，只有更新一行才退库。这两条既有约束正确，予以保留。

上述路径均在 `src/main/java/org/example/takeout` 下。旧方法名是修改前定位标识；当前实现位置见下一节。

## 2. 修改后调用链

### 正常支付

1. `StripePaymentService.createOrReuseOrderPaymentIntent` 校验当前用户及金额，调用 `beginOrderPayment`。
2. 支付短事务调用订单服务 `beginStripePayment`：CAS WAIT_PAY → PAYING，数据库写 `payment_started_at`。同一事务创建/复用金额、币种和稳定幂等键，提交后才返回编排层。
3. 编排层 `recoverOrderPayment` 先在另一个短事务占位：把 `next_retry_at` 前推并累加 `retry_count`。create 前再核对一次：远端 ID 是否仍为空、协调状态是否仍待处理、幂等窗口是否足够。
4. 无事务、无行锁调用 Gateway create；已有 ID 则 retrieve。create 成功本身不表示已付款。
5. create 首次绑定、retrieve 和 Webhook 均进入事务服务的 `applySnapshot`。校验环境、ID、原金额、订单当前总额、CNY 币种、订单 metadata，以及 succeeded 实收金额。
6. succeeded：订单服务仅执行 PAYING → PAID，并写 `pay_time`；支付记录和订单共同提交，首次成功提交后才通知商家。已经 PAID 或进入后续履约状态的重复成功不重发通知。
7. 未完成原生状态保持 PAYING；只有支付期限内的 PAYING 可收到 clientSecret。不会将创建成功或 requires_action/processing 误当付款成功。

主要位置：`StripePaymentService.java:45,91`、`StripePaymentTransactionService.java:45,74,123`、`OrderService.java:326,341`。客户端主动 sync 与后台恢复共用同一套占位与退避，过早重复请求可返回本地状态，不额外请求 Stripe。

### 与超时取消竞争

- 支付先完成 WAIT_PAY → PAYING：原超时 CAS 影响零行，不取消、不退库；用户取消同样不能越过 PAYING。
- 超时先完成 WAIT_PAY → CANCELLED：订单取消和库存回补先提交；支付 CAS 影响零行，锁定读取最新状态后拒绝，不创建 Stripe 对象。
- 支付 CAS 零行时若最新状态仍是 PAYING，则复用已有支付记录；不是一律报数据库故障。最新状态使用 locking read，避免 MySQL RR 下沿用预读快照。
- 保留创建后严格 30 分钟的资格边界：支付条件为 create_time > cutoff，原超时取消为 <= cutoff；不使用 payment_started_at 延长支付期限。

### 失败恢复与长期未完成

- `StripePaymentRecoveryTask` 按到期索引扫描 PENDING。前台和后台都先抢占同一条记录——把 `next_retry_at` 推到未来——提交占位后才执行 HTTP。
- 无 PaymentIntent ID：仅在本地准备起 23 小时的保守窗口内复用原键和原 create 参数，并预留两分钟请求余量。有 ID：始终 retrieve，不依赖 create 幂等键有效期。
- create 超时、响应丢失、本地绑定异常都保留已提交的 PAYING 和原键。失败后无需单独收尾：占位时已按退避把 `next_retry_at` 推到未来，下一个周期自然可被重新领取；进程崩溃同理。占位时长取「退避间隔」与 60 秒的较大者，保证网关调用期间（连接 5s + 读取 10s + 一次重试）不会被第二个 worker 重复领取。
- 退避为 15、30、60、120、240、480、960、1800 秒，随后上限 1800 秒。默认最多执行 96 次，下一次领取转 REVIEW。到期时间与退避统一取数据库时钟。
- 可配置参数为 `payment.stripe.recovery-initial-delay-ms=15000`、`recovery-delay-ms=15000`、`recovery-batch-size=50`（限 1–200）和 `recovery-max-attempts=96`。任务只在 Stripe 启用时存在；恢复线程与原订单超时调度分离。
- 超过订单 30 分钟且远端尚未终结：在事务外请求 Stripe cancel，然后应用可信结果。只有 canceled 且实收为 0 才允许订单 PAYING → CANCELLED，事务归还库存；processing 或未知状态继续持有库存并退避。取消与确认在 Stripe 竞争时，下次 retrieve/成功 Webhook 可确认 PAID。
- 网关异常分两类：**Stripe 明确拒绝**（4xx，如 `payment_intent_unexpected_state`）表示远端状态已然确定，取消被拒时改为 `retrieve` 拉取真实状态并按它收场，避免订单卡在 PAYING 直到重试耗尽；**结果未知**（连接/读取超时、5xx、限流）表示远端可能已经生效，必须原样抛出交给对账核实。两类都保留原始 SDK 异常作为 `cause`，消息只含分类、HTTP 状态码和 Stripe 错误码，不含响应体或密钥。
- 创建窗口已过且没有远端 ID、预算耗尽：持久化 REVIEW、停止自动 HTTP、记录 ERROR 日志，保留订单和库存供人工核实。绝不自动生成第二个键或第二笔支付。

Stripe v1 幂等键达到 24 小时后可能被清除，再使用可能创建新对象；23 小时及请求余量是本实现的保守边界。[Stripe 幂等请求说明](https://docs.stripe.com/api/idempotent_requests)。processing 并非总能取消，因此取消失败必须继续查询而不能直接本地取消。[Stripe PaymentIntent 取消规则](https://docs.stripe.com/api/payment_intents/cancel)。

### Webhook

- Gateway 保留原始请求体签名验证和测试环境限制，业务层只接受已验签快照。
- 本地已关联支付进入与首次绑定/retrieve 相同的 `applySnapshot`；锁顺序固定为 orders → order_stripe_payments。
- 事件去重记录在支付和订单副作用成功后才插入，三个部分共同提交或回滚。订单确认 false、数据库异常、库存异常都不会提交成功去重标记，HTTP 返回非 2xx 供重投。失败事务回滚后单独保存安全诊断；原已协调完成却出现终态冲突的支付转 REVIEW，不能只有重投错误而没有可见异常记录。
- 本地已有待恢复记录但 ID 尚未绑定：返回可重试异常，不仅凭签名或 metadata 盲目绑定。原键恢复绑定后可重试同事件。
- 无本地支付记录的其他账户活动忽略，不插入事件、不关联任意远端对象。
- succeeded/canceled 不被较早未完成快照回退；canceled 后又收到 succeeded 视为冲突拒绝应用，保留原终态。重复成功不重发商家通知。

## 3. 文件变更清单

以下生产文件均在 `src/main/java/org/example/takeout/`：

- `Order/Mapper/OrderMapper.java`：增加锁定读、资格开始时间、可信 Stripe 取消 CAS、支付记录存在检查；分离旧模拟支付 SQL。
- `Order/Service/OrderService.java`：拆出资格 CAS、单次成功确认和已证实远端取消；复用库存回补与 afterCommit；保留关闭 Stripe 时的模拟支付。
- `Order/Service/OrderCommandService.java`：删除无效 refunding 固定成功方法；阻止 Stripe 已付订单假退款取消；保留非 Stripe 模拟订单拒单。
- `Merchant/Controller/MerchantControllerSold.java`：修正“模拟退款成功”的接口注释，未改其他商家行为。
- `Payment/Entity/OrderStripePayment.java`：映射本地协调状态、重试次数与下次到期时间，明确不同于 Stripe 原生状态。
- `Payment/Mapper/OrderStripePaymentMapper.java`：复用原表，增加到期扫描、占位式领取、协调状态更新与数据库时钟读取。
- `Payment/Service/StripePaymentTransactionService.java`：资格与记录原子保存、统一快照应用、检查订单转换结果、终态保护、事件原子去重、恢复预算与退避。
- `Payment/Service/StripePaymentService.java`：NEVER 编排边界、原键恢复、retrieve、到期远端取消；只为期限内 PAYING 返回客户端确认信息。
- `Payment/Gateway/StripePaymentGateway.java`：声明 PaymentIntent 取消能力。
- `Payment/Gateway/StripePaymentGatewayImpl.java`：实现带稳定取消键的 SDK cancel，只负责外部 API 和快照映射；保留原签名验证及测试环境校验。
- `Payment/Config/StripePaymentRecoveryTask.java`：新增有批量上限的数据库恢复任务，逐记录隔离失败。
- `Payment/Config/StripePaymentConfiguration.java`：新增专用恢复调度线程，保留默认调度器供订单超时等既有任务使用，防止慢 HTTP 占用原定时任务线程；保留既有 SDK 超时/网络重试设置。
- `Payment/Controller/StripeWebhookController.java`：任务开始时已有非 2xx 异常处理修改，本轮保留，未额外改动。

数据库文件：

- `src/main/resources/db/migration/V20261105__stripe_payment_recovery.sql`：新增字段、索引、约束与旧数据保护预检/回填。
- `src/main/resources/db/migration/V20261106_1__drop_stripe_recovery_lease_columns.sql`：移除租约、幂等过期时间与诊断列（2026-11-06 追加）。
- `deploy/migrations/`：上述两份迁移的部署审阅镜像。
- `deploy/schema.sql`：空库初始化快照同步字段、索引和约束；旧版本迁移内容不修改。

测试文件均在 `src/test/java/org/example/takeout/`：

- `Payment/Service/StripePaymentTransactionServiceTest.java`：真实 H2/MyBatis/Spring 事务、真实订单和商品库存 SQL；覆盖状态、副作用、并发、恢复及回滚，外部 Gateway/SSE 为替身。
- `Payment/Service/StripePaymentServiceTest.java`：更新资格、恢复占位和编排契约，保留鉴权、金额和 Webhook 类型边界测试；新增「取消被拒后回落到真实状态」与「取消超时向上抛出」两个用例。
- `Payment/Gateway/StripePaymentGatewayImplTest.java`：校验取消请求的 ID、稳定键及返回快照无 clientSecret；新增异常分类用例，确认 `InvalidRequestException` 映射为确定拒绝、`ApiConnectionException` 映射为结果未知，且两者都保留 SDK 原始异常。
- `Payment/Config/StripePaymentConfigurationTest.java`：校验专用恢复调度器与原调度器分开。
- `Order/Service/OrderCommandServiceTest.java`：验证真实退款未实现时拒绝取消；关闭 Stripe 后旧支付记录仍不能走假退款；保留模拟拒单测试。
- `Order/Service/OrderPaymentNotificationTransactionTest.java`：将旧模拟支付异常用例名称改为实际的模拟支付事务失败，避免继续宣称模拟路径进入 PAYING；测试断言保持不变。
- `integration/FlywayMigrationIntegrationTest.java`：提升最新版本与迁移数量，新增旧支付数据保护、幂等键过期 REVIEW、约束和孤立 PAYING 阻断测试；使用随机 MySQL 库。
- `integration/OrderServiceIntegrationTest.java`：补齐关闭 Flyway 的旧 MySQL 夹具需要的支付关联表，保留原取消和库存集成用例。
- `integration/StripePaymentSchemaTestFixture.java`：复用实际建表迁移初始化旧测试库中的支付表，不模拟表行为。
- `integration/MysqlApiIntegrationTest.java`：重建共享测试库前先删除 Stripe 子表，避免真实外键阻止删除 orders；仅修改测试建库顺序。

文档文件：

- `docs/stripe-test-payments.md`：更新支付、恢复、取消、退款与迁移边界。
- `docs/database-migrations.md`：补充新迁移及历史数据预检说明。
- `docs/IdempotencyDesign.md`：纠正旧双 UPDATE 与假退款描述。
- `docs/architecture/01-system-overview.md`：更新系统支付能力和退款限制。
- `docs/architecture/03-order-lifecycle.md`：更新支付/取消状态图、事务与边界。
- `docs/architecture/README.md`：更新支付能力范围说明。
- `docs/project-guide.md`：更新支付流程与已知限制。
- `docs/状态流转表.md`：更新 PAYING 持久化、可信取消及退款限制。
- `docs/项目面试准备报告.md`、`docs/Java后端实习面试题库.md`：移除把旧模拟退款当作当前能力的说法。
- 本报告：记录真实实施、验证和未实现边界。带历史日期的既有审查记录保留其历史结论。

## 4. 事务边界

- 入口编排使用 NEVER，已有本地事务时直接拒绝，不把仍可能持锁的外层事务挂起后继续 HTTP。
- 准备事务：订单 CAS、payment_started_at、本地支付记录、幂等键共同提交。准备失败全部回滚，HTTP 不执行。
- 占位事务：`retry_count + 1` 与 `next_retry_at` 前推一起提交。create 前的一致性复核为独立短只读事务，结束后执行 HTTP。
- create/retrieve/cancel：均在实际数据库事务之外，由 Gateway 执行；不持有订单或支付行锁。
- 结果事务：首次 ID 绑定/原生状态、订单确认或取消、库存回补、Webhook 去重共同提交或回滚。订单服务加入同一事务。
- 商家通知：首次 PAYING → PAID 后注册 afterCommit，最外层事务提交后才发送；回滚不发，重复成功不再注册。
- 失败收尾：不再有单独的释放事务。占位时已把 `next_retry_at` 按退避推到未来，网关失败后无需额外写入；进程崩溃时占位同样会在到期后自动释放。
- 对外只读方法使用独立短只读事务，以免编排的空事务同步上下文复用 MyBatis 一级缓存，向前端返回旧订单状态。这里没有扩大数据库锁的生命周期。

## 5. 数据库迁移

`orders.payment_started_at DATETIME NULL` 只由取得资格的 CAS 写入；暂不扩充通用 Order 实体查询字段。`pay_time` 只在确认成功时写入。

原 `order_stripe_payments` 新增：`reconciliation_state`（PENDING/COMPLETE/REVIEW）、`retry_count`、`next_retry_at`。2026-11-06 又移除了 `lease_token`、`lease_until`、`idempotency_expires_at`、`last_error`：占位由 `next_retry_at` 承担，幂等有效期由 `create_time + 23h` 推导，诊断信息走日志。任何情况下都不保存 clientSecret、密钥、完整远端响应或含支付信息的异常消息。

新增 `idx_stripe_recovery_due(reconciliation_state,next_retry_at,order_id)`；新增协调状态枚举与重试次数非负 CHECK。原 order_id 主键、远端 ID 唯一约束、幂等键唯一约束、金额/CNY 约束和外键保留。

迁移在 DDL 前阻止无支付记录的孤立 PAYING。旧 WAIT_PAY + 支付记录改为 PAYING，避免被原超时任务误关；历史开始时间只能以支付记录 create_time 近似回填，不伪造成功时间，不在迁移中退库存。终态一致的记录标 COMPLETE；过期且无远端 ID 的未知创建标 REVIEW。订单 update_time 保持原值。字段增加有存在检查，支持已同步的 deploy/schema.sql；旧 Flyway 成功校验和不修改。

发布时必须停止旧版本支付/取消写入，执行迁移，再启动新版本；不能让旧版本超时任务与数据保护迁移并行修改订单。MySQL DDL 不是整份脚本原子回滚，预检失败或中途故障应按 Flyway 运维流程处理，不可直接 repair 掩盖成功历史。

## 6. 测试与验证

项目 `pom.xml` 要求 Java 17，本机 Microsoft JDK 17.0.18 匹配；未更改全局 JAVA_HOME/PATH。RTK 尝试后没有产生可用摘要，终止阻塞运行，改用原生 Maven；Mockito 使用项目实际依赖的显式 javaagent 避免动态自附着阻塞。正式统计以完成运行的 Maven/Surefire 结果为准。

数据库场景覆盖：

1. 正常准备与提交后 create，Webhook 确认，以及首次绑定即 succeeded。
2. 两个并发完整支付请求共享一个持久化支付记录和占位；另有并发资格准备复用同键。
3. 支付与超时取消两种可控交错，分别验证赢家状态、HTTP 是否发生及库存。
4. create 超时/响应未知保持 PAYING、退避后同键恢复。
5. 真实数据库 CHECK 注入绑定失败，原键和 PAYING 保留，重试恢复。
6. 无 ID 同键 create；有 ID 即使幂等截止已过也 retrieve。
7. 幂等窗口过期未知创建、重试预算耗尽转 REVIEW，不再 create。
8. 重复/不同事件成功、乱序失败或取消、Webhook 与主动查询并发。
9. 订单确认 CAS 失败与数据库确认异常，事件和支付结果一起回滚，可重投。
10. 已经 PAID 重复 succeeded 不重复通知。
11. payment_failed 保持支付中；长期未完成先请求远端 cancel；processing/cancel 失败不退库；确认与取消竞争后 retrieve 收敛。
12. 最外层事务回滚与 afterCommit，提交前无商家通知，成功只通知一次。
13. 取消重复不重复退库、库存异常整体回滚、商家重设 stock_version 后保留新库存基准。
14. 假退款不能取消 Stripe 已付订单；canceled 快照若仍有实收金额也拒绝假退款式取消。
15. 占位到期后可重新领取、retry_count 累加；创建前重新检查窗口、绑定状态与幂等有效期；未知状态与未绑定 Webhook 的恢复。

最终专项命令（PowerShell，参数均为字面值）：

```powershell
mvn -o '-Dtest=StripePayment*Test,OrderPaymentNotificationTransactionTest,OrderCommandServiceTest,OrderTimeoutCancellationTest,OrderTimeoutTaskTest,OrderServiceIntegrationTest,FlywayMigrationIntegrationTest' '-Dtakeout.migration-test.enabled=true' '-DargLine=-javaagent:E:/maven_repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar' test
```

结果：164 项通过，0 断言失败，0 错误，0 跳过，BUILD SUCCESS。其中支付数据库事务用例 37 项、真实 MySQL 迁移 30 项、旧订单服务 MySQL 集成 38 项，均通过。完整日志：`target/stripe-final-verification.log`。

全量命令：

```powershell
mvn -o '-Dtakeout.migration-test.enabled=true' '-DargLine=-javaagent:E:/maven_repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar' test
```

全量运行未通过：1145 项中 1061 通过、0 断言失败、48 错误、36 跳过。日志：`target/stripe-verification-all.log`。错误包括 35 项本机 Redis 127.0.0.1:6379 连接被拒绝（实验购物车 27、GEO 2、登录限制 6），以及 13 项在套件运行早期发现共享旧测试库缺少 order_item 的夹具错误；后者源于前一轮重建被支付外键阻止后留下的部分结构，已修复子表删除顺序并完成后续重建。本次没有修改 Redis 相关业务模块来规避失败。

这轮全量结果早于最后新增的 Webhook 终态冲突诊断测试；最终支付修改已由上述 164 项专项重新验证，不能把全量结果写成全部通过。后续单独复跑共享测试库场景：

```powershell
mvn -o '-Dtest=CartServiceIntegrationTest,CategoryProductServiceIntegrationTest' '-DargLine=-javaagent:E:/maven_repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar' test
```

结果：13 项通过，0 断言失败，0 错误，0 跳过，BUILD SUCCESS。日志 `target/stripe-shared-schema-regression.log`。这确认共享测试库重建后，上述 13 项初始化错误不再出现；35 项 Redis 环境错误尚未解决，未宣称全量绿色。

迁移使用独立随机 MySQL 库；业务集成使用仓库原有 takeout_integration_test。没有调用真实 Stripe API。

## 7. 剩余风险与人工处理

- REVIEW 有数据库记录和 ERROR 日志，但没有自动工单/告警接收器或管理后台。需要运维监控 REVIEW，结合 Stripe 请求日志、订单 metadata 和支付记录核实；不能把长期 REVIEW 当作自动取消。可使用只读 SQL 查看待核实和已超时的支付中订单：

```sql
SELECT o.id, o.status, o.payment_started_at, o.pay_time,
       p.payment_intent_id, p.reconciliation_state, p.retry_count, p.next_retry_at
FROM orders o LEFT JOIN order_stripe_payments p ON p.order_id = o.id
WHERE p.reconciliation_state = 'REVIEW'
   OR (o.status = 1 AND o.create_time <= NOW() - INTERVAL 30 MINUTE)
ORDER BY o.create_time, o.id LIMIT 100;
```
- 无 ID 且窗口过期时，只有人工确认原远端对象或确认未创建，才能安排后续处理；不可清空记录或换键盲目重付。本次没有自动使用 Stripe 搜索结果冒充不存在证明。
- 人工确认正确 PaymentIntent ID 后，需经金额/币种/订单校验再恢复记录，明确重置 retry_count 并安排 next_retry_at；实际结果仍交给 retrieve/统一事务应用，不直接把数据库改 PAID/CANCELLED。无人工处理 API，不能把这段运维要求当作已实现功能。
- processing/取消失败可能长期占库存，自动重试预算用尽后 REVIEW。退款未实现时，已收款但本地订单不可确认、终态冲突或取消快照仍有收款，需要人工处理资金；不得恢复库存来伪装退款。
- SSE 仍是原来的进程内提醒，afterCommit 与 CAS 保证不重复触发，但不是可靠持久化投递；进程在提交后退出可能丢提醒，保留原未读补查机制，没有引入 outbox/MQ。
- 订单 30 分钟仍使用既有应用时区/创建时间规则；部署需保持应用与数据库时区一致。恢复占位与退避使用数据库时钟，没有改全库隔离级别。
- 当前 Gateway 仍只允许 Stripe 测试环境。实际 Stripe 沙盒、网络超时、真实事件重投及权限配置没有在本次外部联调，测试使用 Gateway 替身和真实本地数据库副作用。

## 8. 未实现事项

真实 Stripe 退款、部分退款、已付订单退款取消/商家拒单退款、退款单与退款 Webhook、退款对账均未实现。未开放生产 live 支付；未实现 Stripe 全量账单对账、人工审核 UI/接口、自动审核工单、可靠通知投递或 Vue 生产支付页改造。保留原关闭 Stripe 时的本地模拟支付，未删除其他领域的实验或测试模拟功能。
