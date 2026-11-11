# 数据库迁移与脚本镜像

最近复核：2026-11-08。SQL 权威来源和部署镜像仍按下述目录管理；支付和退款迁移说明见文末。

`src/main/resources/db/migration` 是 Flyway 迁移的权威来源。应用启动时仅扫描该 classpath 目录，`deploy/migrations` 保存同名、同内容的 SQL 镜像，便于部署审阅。当前两个目录各有 26 份 SQL，文件名与内容保持一致。

部署镜像目录 `deploy/migrations` 同步权威目录的所有 `B`、`V` SQL。`B20261008__initial_schema.sql` 用于已创建的空数据库，在应用启动时累计建表，再执行 `V20261009` 及以后的迁移；已有 Flyway 迁移历史的数据库会忽略该基线，继续升级。[Flyway 官方累计基线说明](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations)介绍了 `B` 脚本的选择和执行规则。

当前最新版本为 `V20261108`。空库执行累计基线后，再执行 `V20261009` 至 `V20261108` 共 17 份后续迁移；已处于 `20261008` 基线的旧库也升级这 17 个版本。

新增变更时，在权威目录创建更高版本的 `V<版本>__<描述>.sql`，再同步到 `deploy/migrations`。同类迁移的版本不得重复；累计基线可以与相同版本的 `V` 脚本并存。超时取消退避使用 `V20261008__timeout_cancel_backoff.sql`。

`flyway_schema_history` 已记录成功的版本必须保持文件名和内容不变。Flyway 会校验其校验和；不要通过修改旧脚本或直接修改迁移历史来修复结构差异，应新增版本迁移。不要手工重复执行已有成功记录的 SQL。

## 新迁移的数据处理

`V20261009` 只为缺失的历史配送奖励快照回填 `5.00` 元，该值来自当前 `DeliveryFeeCalculator` 固定奖励规则。已有奖励及时间字段保持原值；新任务继续由应用计算奖励。

`V20261010`、`V20261011` 在修改目标业务数据或约束前，使用临时表的命名 `CHECK` 约束进行预检。约束只用于本次会话，不会添加到业务表。历史 `V20261012` 将 `merchant.username` 和作为骑手登录名的 `rider.name` 改为 `utf8mb4_0900_as_cs`，但大小写/重音敏感规则仍可能把部分不同 Unicode 字符序列视为相同。

`V20261013__login_name_binary_collation.sql` 是此前已执行过的二进制排序迁移，保留文件及校验和。当前方案由 `V20261014__restore_login_name_as_cs.sql` 恢复 `user.username`、`merchant.username`、`rider.name` 的 `utf8mb4_0900_as_cs`；输入边界由三个角色注册、登录共六个 DTO 的统一 `@Pattern` 限制为英文字母、数字、下划线、连字符，长度 1–50。Redis 原文 key、账号数据及基线/历史迁移均不改写。

V14 在任何列修改前，先检查三张表是否有不同名称在 `as_cs` 下合并为同一唯一键。任一冲突会中止迁移；不得静默合并、删除或改名。预检只用会话临时表，不给业务表增加 CHECK 或改变其他字段。现有业务库是否已应用 V14，仍须以其 Flyway 历史和列定义为准。

`V20261017__merchant_order_seen.sql` 为订单增加 `merchant_seen TINYINT(1) NOT NULL DEFAULT 0`，并增加 `idx_order_merchant_status_seen_create_time_id (merchant_id, status, merchant_seen, create_time, id)`，供商家首次打开页面或 SSE 重连后补查未查看待接单订单。迁移先补可空字段，再将缺失标记按 `WAIT_PAY=0`、`PAYING=1`、`PAID=2` 回填为 `false`，其他状态回填为 `true`；已有 `true` 保留，其他状态已有的 `false` 也归为 `true`，回填不改变 `update_time`。列与同序完整五列索引已有存在检查，但不能据此手工重跑 Flyway 已记录成功的版本。新订单默认未查看，页面展示后由 ACK 确认，SSE 发送成功不会提前更新该字段。

`V20261018__order_cancel_source.sql` 只增加可空的 `cancel_source TINYINT NULL DEFAULT NULL`，用于记录 `1` 用户主动取消、`2` 商家拒单、`3` 系统超时取消、`4` 平台取消。迁移不回填历史数据：旧订单无法可靠区分用户取消与超时取消，因此已取消订单保持 `NULL`；非取消订单也为 `NULL`。列存在检查保证重复执行安全，已成功记录的版本不要手工重跑。应用侧用户取消、超时任务和商家拒单分别在条件更新中写入 `1`、`3`、`2`，平台取消 `4` 尚无入口。

`V20261026__stock_version.sql` 为 `product` 增加独立的 `stock_version INT NOT NULL DEFAULT 0` 库存基准版本，为 `order_item` 增加 `stock_version INT NOT NULL DEFAULT 0` 预扣版本快照。历史行默认 `0`，与迁移前商品的初始基准一致；商家第一次显式重设库存后才推进到 `1`。迁移不改变历史订单行，也不回填无法还原的旧版本关系。应用侧只在商家显式提交 `stock` 的更新中执行 `stock_version = stock_version + 1`，订单预扣、取消回补、名称/价格修改和上下架均不推进该列。回补库存 SQL 以 `stock_version` 为条件，版本不一致时跳过。两个列都有存在检查，可在 `deploy/schema.sql` 已建列的新库上重复安全执行；已成功记录的版本不要手工重跑。

`V20261027__merchant_pickup_coordinates.sql` 为 `merchant` 增加可空的 `longitude`、`latitude`（`DECIMAL(10,7)`），用于把出餐后的配送任务写入 Redis GEO `delivery:available:geo`；商家可经 `PUT /merchant/info` 成对设置坐标，经度范围 `[-180, 180]`、纬度范围 `[-85.05112878, 85.05112878]`，最多 7 位小数。同时为 `delivery_task` 增加 `pickup_longitude`、`pickup_latitude` 坐标快照。历史商家与历史任务保持 `NULL`；商家未配置坐标时，出餐后跳过 GEO 写入并记录日志，不阻断已提交的出餐事务。`deploy/schema.sql` 同步包含四列；新列均有存在检查，可在部署初始化已建列的空库上重复安全执行。

应用在创建配送任务时保存商家坐标快照，出餐数据库事务提交后按任务当前 MySQL 行修复 GEO。抢单事务提交后移除任务 GEO；立即删除失败时尝试单任务对账。定时全量对账首次等待 10 分钟，此后每轮完成后再等待 10 分钟：MySQL 单次查询读取全部 `WAIT_ASSIGN` 且未分配骑手的任务，Redis ZSET `range(0, -1)` 读取 GEO 全部成员，只对两边成员差集逐项修复。每个差异任务在独立 `REQUIRES_NEW`、`REPEATABLE_READ` 事务中执行 `SELECT ... FOR UPDATE`，直到对应 Redis 操作完成；两边都存在的成员不逐项刷新坐标，因此该对账只收敛成员集合，不校正已存在成员的坐标漂移。这是运行时代码行为，本轮没有运行真实 Redis/MySQL 对账。

| 失败约束名 | 数据问题 |
|---|---|
| `chk_cart_header_single_merchant` | 同一用户的购物车包含多个商家 |
| `chk_cart_header_user_exists` | 购物车引用不存在的用户 |
| `chk_cart_header_positive_merchant` | 购物车商家 ID 小于等于 0 |
| `chk_product_category_not_null` | 商品分类 ID 为 NULL |
| `chk_product_category_exists` | 商品引用不存在的分类 |
| `chk_user_login_name_as_cs_unique` | User 登录名在 `as_cs` 下发生唯一键合并冲突 |
| `chk_merchant_login_name_as_cs_unique` | Merchant 登录名在 `as_cs` 下发生唯一键合并冲突 |
| `chk_rider_login_name_as_cs_unique` | Rider 登录名在 `as_cs` 下发生唯一键合并冲突 |

可在目标数据库用以下只读 SQL 定位问题：

```sql
SELECT user_id, COUNT(DISTINCT merchant_id) AS merchant_count
FROM cart GROUP BY user_id HAVING COUNT(DISTINCT merchant_id) > 1;

SELECT c.id, c.user_id
FROM cart c LEFT JOIN `user` u ON u.id = c.user_id
WHERE u.id IS NULL;

SELECT id, user_id, merchant_id FROM cart WHERE merchant_id <= 0;

SELECT id FROM product WHERE category_id IS NULL;

SELECT p.id, p.category_id
FROM product p LEFT JOIN category c ON c.id = p.category_id
WHERE p.category_id IS NOT NULL AND c.id IS NULL;
```

出现预检错误时，先明确并处理对应的业务数据，再核对失败迁移已执行的步骤和历史记录，按 Flyway 失败迁移恢复流程重试。MySQL DDL 可能分步提交，不能假定整次迁移已回滚。不要修改已成功版本，也不要使用 `repair` 更新成功版本的校验和来掩盖实际结构或数据差异。

## 同步镜像

从项目根目录同步部署镜像：

```powershell
Get-ChildItem -LiteralPath 'src/main/resources/db/migration' -Filter '*.sql' -File |
    ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path 'deploy/migrations' $_.Name) -Force
    }
```

同步后应核对两个目录的 SQL 文件名集合和内容。`deploy/schema.sql` 负责空库初始化，已有数据库的升级由 Flyway 新版本完成。

## Stripe 支付恢复迁移（2026-11-05）

`V20261105__stripe_payment_recovery.sql` 新增订单 `payment_started_at` 和支付记录的协调状态、幂等截止时间、重试次数、下次重试时间、租约及安全诊断码；增加状态/次数约束及到期扫描索引。旧支付记录对应的 `WAIT_PAY` 转为 `PAYING` 防止被超时取消，成功时间不在迁移中伪造；已有终态一致记录标为协调完成。创建结果未知且原键窗口已过的记录标为 `REVIEW`。迁移在任何结构修改之前检查孤立 `PAYING`，若无本地支付记录则停止，需先核实远端，禁止直接补一个新幂等键。

权威 SQL 与部署镜像同内容，空库快照同步新字段；旧迁移不改校验和。恢复参数、测试与人工核实边界见 [支付重构交付报告](stripe-payment-refactor-report.md)。

## 移除恢复租约与冗余列（2026-11-06）

`V20261106_1__drop_stripe_recovery_lease_columns.sql` 删除 `lease_token`、`lease_until`、`idempotency_expires_at`、`last_error` 四列，保留 `reconciliation_state`、`retry_count`、`next_retry_at`。

- 租约原本只为多实例并发恢复同一笔支付而存在。占位改由 `claimAttempt` 把 `next_retry_at` 推到未来完成：第二个调用者的 `next_retry_at <= now` 条件随即不成立，互斥效果等价，且进程崩溃后只需等一个退避周期即可重新领取。
- `idempotency_expires_at` 恒等于 `create_time + 23h`，属于可推导数据，改为按 `PaymentPolicy.IDEMPOTENCY_TTL` 现算。
- `last_error` 只用于运维排查，不参与任何业务判断，改由应用日志的 `reason` 字段承载。

`V20261105` 保持原样不修改校验和；新库会先加列再删列，这是迁移历史的真实痕迹，不影响最终结构。

## Stripe 订单退款表（2026-11-08）

`V20261108__stripe_order_refunds.sql` 新增 `order_stripe_refunds`，以 `order_id` 为主键，每笔订单最多保存一条退款记录。`refund_id` 可空且唯一，供远端创建结果确认后关联；`idempotency_key` 必填且唯一，供同一退款操作重试时复用。金额使用最小货币单位，币种、Stripe 状态及本地对账状态单独保存；`reconciliation_state` 必填且无默认值，由写入方显式指定。

`create_time`、`update_time` 默认取数据库当前时间，记录变更时数据库自动更新 `update_time`。部署镜像及 `deploy/schema.sql` 同步该表；本次只增加数据结构和实体，退款请求、回调及恢复逻辑尚未接入。
