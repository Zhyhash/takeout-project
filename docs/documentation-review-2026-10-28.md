# 2026-10-28 GEO 文档同步与仓库导航清理

本轮按当前工作区源码、SQL、配置及测试代码静态核对，包含未提交改动；只修改文档，不修改业务代码、测试、SQL 或 Git 忽略规则。本轮没有启动应用、连接数据库/Redis 或重新执行测试。

## 当前实现与同步范围

- 骑手位置已有 `PUT/GET/DELETE /rider/location`，只操作当前有效骑手本人。大厅用该位置查询附近配送任务；当前没有附近骑手查询或自动派单入口。
- 商家成对维护取货经纬度，出餐创建配送任务时保存坐标快照。出餐提交后修复任务 GEO，抢单提交后删除；失败记录日志并由定时任务补偿，不回滚已经提交的业务事实。
- 大厅不分页：最多 100 个有效 GEO 候选按距离、任务 ID 升序优先展示，其余任务按 ID 升序。无候选或 GEO 查询失败时，MySQL 按 ID 倒序最多返回 500 条；位置记录不存在时提示先上传。
- 全量对账一次查询全部 MySQL 待接任务，以 Redis `range(key, 0, -1)` 读取全部成员，比较 ID 差集后逐任务修复；没有游标、keyset 分页或连续三次失败即停止的逻辑。两边共同 ID 的坐标不在全量对账中校正。
- 单任务修复在 `REQUIRES_NEW`、RR 事务中按主键 `FOR UPDATE`，锁覆盖状态判断与 Redis 操作。它会校正该任务位置，或移除不存在、已领取、缺少有效坐标的任务。全量读取的内存成本和持锁 Redis 操作的等待仍属于当前边界。
- 取货坐标迁移为 `V20261027__merchant_pickup_coordinates.sql`；当前权威目录和部署镜像各有 19 份 SQL，基线后有 10 份迁移。本轮文件对照不表示已升级任何数据库。

实现定位：[骑手位置入口](../src/main/java/org/example/takeout/Rider/Controller/RiderLocationController.java)、[接单大厅与抢单](../src/main/java/org/example/takeout/DeliveryTask/Service/DeliveryTaskService.java)、[任务 GEO 与对账](../src/main/java/org/example/takeout/DeliveryTask/Service/DeliveryTaskGeoService.java)、[全量查询 SQL](../src/main/java/org/example/takeout/DeliveryTask/Mapper/DeliveryTaskMapper.java)、[出餐编排](../src/main/java/org/example/takeout/Merchant/Service/FulfillmentService.java)。

导航及流程同步到[项目说明](project-guide.md)、[架构总览](architecture/01-system-overview.md)、[模块依赖](architecture/07-module-dependencies.md)、[履约流程](architecture/03-order-lifecycle.md)、[状态流转](状态流转表.md)、[数据库结构](数据库结构.md)、[迁移说明](database-migrations.md)及[GEO 降级实验](experiments/delivery-task-geo-fallback.md)。[2026-10-26 核查](documentation-review-2026-10-26.md)和[架构历史](architecture-refactoring-history.md)保留原日期快照，并指向本次补充。

## 仓库链接与本地材料

根 README、文档索引、项目总说明和历史记录不再链接被 `.gitignore` 忽略的本地学习材料；Redis 相关导航改为已纳入仓库的项目说明和架构章节。本地材料及忽略规则保持原状。

链接检查以 Git 跟踪文件和未忽略的新增文件为范围，同时检查目标是否存在、是否可随仓库提交、引用定义、章节锚点和代码围栏。仅检查本机目标存在不足以保证干净检出后可阅读。

本轮静态检查覆盖 21 份 Markdown、407 个本地及引用式链接目标，未发现缺失目标、指向忽略文件的链接、失效章节锚点、未定义引用或未闭合代码围栏。文档 `git diff --check` 通过；两个迁移目录各 19 份 SQL，文件名及 SHA-256 一致。未运行 Mermaid 渲染或访问外部文档链接。

## 测试证据与提交前差异

2026-10-28 较早的 GEO 修复阶段，RC 版本曾通过生产代码编译、137 项定向单元/API 回归及 4 项 H2 事务测试（均为 0 失败、0 错误、0 跳过）。H2 使用真实 Mapper 与 Spring 事务，覆盖 205 条任务全量查询、抢单并发、出餐 `afterCommit` 独立事务以及 Redis 异常释放行锁。真实 MySQL、Redis 集成测试当时未运行，不能由这些结果替代。

当前 [GEO 服务](../src/main/java/org/example/takeout/DeliveryTask/Service/DeliveryTaskGeoService.java)已改用 `ISOLATION_REPEATABLE_READ`，但 [GEO 单元测试](../src/test/java/org/example/takeout/DeliveryTask/Service/DeliveryTaskGeoServiceTest.java)的 `reconcileAvailableTaskGeoUsesIndependentTransactionAndCommitsAfterLockedGeoWrite` 仍断言 `ISOLATION_READ_COMMITTED`。这是提交前尚未同步的测试预期，本轮仅报告，不修改测试；之前的 141 项通过不能作为当前 RR 版本的通过证据。

测试来源：[H2 事务回归](../src/test/java/org/example/takeout/integration/DeliveryTaskGeoTransactionIntegrationTest.java)、[真实 MySQL 并发回归](../src/test/java/org/example/takeout/integration/DeliveryTaskGeoReconciliationIntegrationTest.java)、[真实 Redis 成员与单任务修复](../src/test/java/org/example/takeout/integration/DeliveryTaskGeoRedisReconciliationIntegrationTest.java)。运行选择及外部依赖要求见[测试说明](project-guide.md#65-运行测试)。
