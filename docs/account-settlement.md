# 商家与骑手内部收益结算

本模块只做数据库内部记账，不代表真实支付。当前没有提现、退款、抽成或冻结操作。
本文以当前 `income_record`、整数类型编码和账户累计收益实现为准。

## 确认收货与结算事务

用户通过 `PATCH /order/{id}/confirm` 确认收货，`OrderService.checkedOrder`
委托 `SettlementService.finishOrder`，在一个 READ COMMITTED 数据库事务中执行：

1. 查询属于当前用户的订单，要求状态为 `DELIVERED`；查询该订单唯一配送任务，
   要求任务为 `COMPLETED`、已绑定骑手，并且接单、送达时间存在且顺序合法。
2. 使用既有条件 UPDATE 将订单推进至 `FINISHED`，同时记录上海时区的完成时间。
   只有影响行数为 1 才允许入账；否则直接返回原有业务错误，不再查询订单状态。
3. 核对金额：商家收入为 `original_amount - discount_amount`，订单
   `total_amount` 必须等于商家收入加 `delivery_fee`；骑手收入使用
   `delivery_task.delivery_reward` 快照，不重新计算配送费。
4. 通过 `IncomeRecordMapper.insert` 分别写入商家、骑手收益流水。
   单条插入依靠数据库异常触发事务回滚，不增加通用写入抽象。
5. 按商家、骑手顺序调用各自 Mapper 的 `creditSettledIncome`，使用同一条 SQL
   原子增加 `balance` 和 `total_income`。

账户 UPDATE 同时检查账户存在、骑手未删除，以及余额、累计收入都不超过
DECIMAL(12,2) 上限 `9999999999.99`。即使 MySQL 使用非严格 SQL 模式，
也会拒绝超限入账，防止字段被截断后流水与账户金额不一致。

任何数据库异常、唯一键冲突、账户缺失或账户更新影响行数不为 1，都会回滚
订单状态、两条流水、双方余额及累计收入。订单金额字段和任务报酬的非空、精度
由现有表结构保证；流水表的 `amount >= 0` 约束拒绝负收入。

并发确认以订单条件 UPDATE 的影响行数作为唯一入账闸门。该 UPDATE 自身取得
订单写锁，同一订单的另一个请求等待提交后只能更新 0 行并失败，不会重复入账。
账户原子 UPDATE 自身取得账户写锁；不额外执行 `SELECT ... FOR UPDATE`。
读取任务不加锁的前提是现有配送状态机不允许合法修改已完成任务的归属与报酬。

重复确认保留业务错误“订单不存在或当前状态不可确认”。即使已完成订单历史上
缺少流水或流水被人工修改，重复请求也不补账、不查询流水、不改变账户金额。

## 流水结构与账户更新策略

`IncomeRecord` 对应 `income_record` 表，Entity、VO 和查询参数使用整数编码：

- `beneficiary_type`：`1` 为骑手，`2` 为商家。
- `type`：`1` 为配送报酬，`2` 为订单收入。
- `BeneficiaryTypeEnum`、`IncomeRecordTypeEnum` 提供固定 `getCode()`；
  Service 显式取编码，不依赖枚举序号或自动枚举映射。
- CHECK 约束只允许骑手配送报酬 `(1, 1)` 和商家订单收入 `(2, 2)`，金额必须非负。
- 唯一键 `(beneficiary_type, beneficiary_id, order_id, type)` 和 `(order_id, type)`
  阻止重复事件及通过更换受益人重复入账；外键关联订单并限制删除。

`Merchant`、`Rider` 的 `balance` 和 `totalIncome` 均使用
`@TableField(updateStrategy = FieldStrategy.NEVER)`，适用于当前实体更新策略。
商家资料更新会先读取完整实体再执行 `updateById`，这些字段必须从通用实体 UPDATE
中排除，避免旧实体覆盖期间发生的结算；骑手实体也遵守相同约束。
该注解不禁止插入，也不限制显式 SQL；结算通过专用原子 SQL 更新两个账户字段。

## 收益查询

`GET /rider/rewards`、`GET /merchant/rewards` 使用当前登录身份，接受
`pageNum`（默认 1）和 `pageSize`（默认 10，上限 100），返回统一 Result：

- `balance`：账户当前余额。
- `totalIncome`：直接读取账户 `total_income`，表示历史累计已结算收入。
- `todayIncome`：按上海时区当日零点（含）至次日零点（不含），对当前账户流水求和。
- `transactions`：项目现有 PageInfo 分页结构，按 `create_time`、`id` 倒序，
  通过 MapStruct `IncomeRecordConverter` 转换为 VO，保留分页信息。

余额、累计收入、今日汇总与分页使用同一个 REPEATABLE READ 只读事务，
共享一致性快照。累计收入不在每次查询时重新聚合，也不等同于可用余额。

## Flyway 与历史数据

迁移资源与 `deploy/migrations` 中的同名脚本保持一致：

- `V20261030__account_settlement.sql`：原有结算迁移，增加余额字段并创建旧字符串编码
  `account_transaction` 表；保留该历史迁移。
- `V20261030_2__income_record_encoding.sql`：创建整数编码 `income_record`，
  显式映射旧字符串编码并复制历史流水，增加账户 `total_income` 并回填。
- `deploy/schema.sql`：新部署直接创建最终字段和 `income_record`；此前迁移创建的
  旧表在后续编码迁移成功后移除。

已有旧流水转换时保留主键、受益人、订单、金额和创建时间。若新旧两表并存，
仍会合并旧流水；相同订单事件、受益类型、受益人及金额的重复记录只保留一份，
沿用新表已有记录。冲突记录不会被静默覆盖，唯一键异常会中止复制并保留旧表。
旧表仅在复制语句成功后删除。两表不同事件若主键 ID 碰撞，也会安全失败，
需要人工核对后处理冲突；迁移不会擅自重新分配历史流水 ID。

`total_income` 按转换后的全部流水 `SUM(amount)` 回填，条件为当前账户的
`beneficiary_type` 和 `beneficiary_id`，没有日期限制。相同数字 ID 的商家与骑手
分开统计，无流水账户为 `0.00`。它不从历史订单金额推算、不依赖订单当前状态，
也不使用或改写现有余额；已经完成但没有结算流水的旧订单不自动补账。

回填使用绝对值重算，避免重复累加。迁移应在应用结算写入停止时执行；正常再次
调用 Flyway `migrate` 不会重复执行已成功的版本。MySQL DDL 不提供整段事务回滚，
失败时需核对剩余结构、修复冲突及 Flyway 失败记录后重试；SQL 可重放不代表
Flyway 会自动修复失败历史。正常结算受账户上限约束；若人工导入的异常账本累计值
超出 DECIMAL(12,2) 范围，应先审账处理，不能依赖非严格 SQL 模式回填。

旧版迁移和 `src/test/resources/db/migration-fixtures/local-schema-20261008.sql`
历史快照保持不变。

## 验证方式

`src/test/resources` 没有需要随最终收益表同步的当前数据库初始化 SQL。
`application.yaml` 配置 H2，历史快照专用于验证旧库升级；手写建表夹具位于 Java
集成测试中，使用账户实体查询的夹具必须包含 `balance`、`total_income`。

结算与迁移 MySQL 测试分别创建随机命名的隔离数据库。迁移用例覆盖纯旧表升级、
新旧表并存与重复事件、冲突保留旧表、空库初始化、部署脚本接入及再次迁移不变。
历史回填用例覆盖跨日期多订单、商家与骑手 ID 相同，以及余额和累计收益不同。
结算用例覆盖并发入账、失败回滚、整数编码、日界统计、非严格 SQL 模式下两个账户
各自的累计收益溢出，以及旧实体 `updateById` 不覆盖入账金额。

仅运行结算相关测试：

```powershell
mvn '-Dtest=MysqlSettlementIntegrationTest,FlywayMigrationIntegrationTest,RewardQueryServiceIntegrationTest,RewardApiTest,SettlementServiceTest,IncomeRecordConverterTest' '-Dtakeout.settlement-test.enabled=true' '-Dtakeout.migration-test.enabled=true' test
```

结算、迁移测试默认连接 localhost:3306、root/root，可分别使用
`takeout.settlement-test.mysql.host/port/user/password`、
`takeout.migration-test.mysql.host/port/user/password` 覆盖。
既有固定名测试库通过 `takeout.test.mysql.port` 覆盖端口，完整回归还需要 Redis。

## 本轮验证（2026-10-30）

在工作区 `target` 下初始化全新 MySQL 9.6.0 测试实例（24306），使用独立 Redis
8.10.3（26379）。既有固定名测试库仅加载核心业务结构，以符合其自行重建表结构的
测试准备方式；完整部署结构另由随机隔离库中的 Flyway 测试验证。未连接业务数据库，
未修改 Stripe 支付模块。

编译命令为 `mvn -o -DskipTests compile`。完整回归使用以下参数，不筛选测试类：

```powershell
mvn -o '-Dtakeout.test.mysql.port=24306' '-Dspring.data.redis.port=26379' '-Dtakeout.settlement-test.enabled=true' '-Dtakeout.settlement-test.mysql.port=24306' '-Dtakeout.migration-test.enabled=true' '-Dtakeout.migration-test.mysql.port=24306' '-Dtakeout.fulfillment-test.mysql.port=24306' '-Dtakeout.geo-reconciliation-test.mysql.port=24306' '-Dtakeout.geo-fallback-test.mysql.port=24306' test
```

编译通过。最终完整回归 **1220 项，0 失败、0 错误、0 跳过**，耗时 2 分 52 秒。
其中 MySQL 结算 22 项、Flyway 28 项、MySQL API 集成 34 项、订单集成 38 项均通过。
编译日志：`target/settlement-handoff-compile.log`；完整回归日志：
`target/settlement-handoff-full-test-verified.log`。本轮启动的两个 MySQL 实例和独立
Redis 在验证后关闭，数据目录与日志保留；未执行人工清表。

尚未在 compose 指定的 MySQL 8、Redis 7 组合上复跑。历史账本事件冲突、不同事件
主键碰撞以及异常导入导致累计金额超限，仍需人工核账处理；迁移不会自动改账。

本轮增量修改文件如下；其余工作区已有修改保持原状：

- 账户入账上限：`src/main/java/org/example/takeout/Merchant/Mapper/MerchantMapper.java`、
  `src/main/java/org/example/takeout/Rider/Mapper/RiderMapper.java`。
- 编码迁移及部署镜像：`src/main/resources/db/migration/V20261030_2__income_record_encoding.sql`、
  `deploy/migrations/V20261030_2__income_record_encoding.sql`。
- 旧测试表名、编码与累计收益断言：`src/test/java/org/example/takeout/integration/MysqlApiIntegrationTest.java`、
  `src/test/java/org/example/takeout/integration/OrderServiceIntegrationTest.java`。
- 迁移、回滚和更新策略回归：`src/test/java/org/example/takeout/integration/FlywayMigrationIntegrationTest.java`、
  `src/test/java/org/example/takeout/integration/MysqlSettlementIntegrationTest.java`。
- 账户建表夹具补列：`src/test/java/org/example/takeout/integration/DeliveryTaskGeoFallbackExperimentIntegrationTest.java`、
  `src/test/java/org/example/takeout/integration/DeliveryTaskLifecycleIntegrationTest.java`、
  `src/test/java/org/example/takeout/integration/MysqlFulfillmentRetryIntegrationTest.java`。
- 实现与验证文档：`docs/account-settlement.md`。
