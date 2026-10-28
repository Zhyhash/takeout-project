# 配送任务 GEO 查询失败降级实验

验证 `DeliveryTaskService.getAvailableRiderTask`：骑手位置或配送任务 GEO 查询抛出
`RedisGeoOperationException` 后，接单大厅仍从 MySQL 返回可接任务，任务 ID 完整，异常不进入客户端响应。

## 记录日期与证据范围

本文件保留了既有实验的场景、步骤和断言；原记录没有可核验的执行日期，因此不把它标成这轮运行结果。该实验使用真实 MySQL 和 MockMvc，但骑手位置及配送任务 GEO 响应由 Mockito 控制，没有验证真实 Redis 命令或 Redis GEO 集合。

2026-10-28 较早版本曾有 137 个定向测试和 4 个 H2 测试通过；之后单任务 GEO 对账事务隔离级别从 `READ_COMMITTED` 调整为 `REPEATABLE_READ`。这些 141 项结果不作为当前源码版本的通过依据。当前 `DeliveryTaskGeoServiceTest` 仍断言旧的 `READ_COMMITTED` 隔离级别，和生产代码不一致；本轮没有修改或运行这个测试。本轮文档复核仅静态核对，未运行测试、数据库迁移，也未连接真实 MySQL 或 Redis。

自动化实验位于
[DeliveryTaskGeoFallbackExperimentIntegrationTest](../../src/test/java/org/example/takeout/integration/DeliveryTaskGeoFallbackExperimentIntegrationTest.java)。
HTTP 请求通过 MockMvc 调用真实 Controller、JWT 拦截器、Service、MyBatis Mapper 和 VO 转换器；
MySQL 使用真实连接，骑手位置与配送任务 GEO 查询通过 Mockito 控制，不需要启动 Redis。

## 运行

在项目根目录执行，需要可用的 MySQL，账号需有创建和删除临时数据库的权限。默认连接
`localhost:3306`，账号密码为测试约定的 `root/root`。

```powershell
mvn "-Dtest=DeliveryTaskGeoFallbackExperimentIntegrationTest" test
```

可通过系统属性调整测试连接，例如改用 Compose 的默认 MySQL 端口：

```powershell
mvn "-Dtest=DeliveryTaskGeoFallbackExperimentIntegrationTest" "-Dtakeout.geo-fallback-test.mysql.port=3307" test
```

支持 `takeout.geo-fallback-test.mysql.host`、`port`、`user`、`password`。
测试创建随机命名的 `takeout_geo_fallback_test_<UUID>` 数据库，只在该库建立 `rider` 和
`delivery_task` 表，结束后删除自有库。连接失败会使实验失败，不会按跳过处理。

## 原实验过程与断言

1. 在 MySQL 中插入有效骑手和三条待接任务 `7101`、`7102`、`7103`，任务均为
   `WAIT_ASSIGN`（`status=0`），且 `rider_id IS NULL`；另插入一条 `DELIVERING` 任务
   `7104` 和一条 `COMPLETED` 任务 `7105`。保存所有任务的 ID、状态和归属，供实验前后比对。
2. 将骑手位置固定为 `(121.4737, 31.2304)`，请求使用缺省半径 `5km`（实际参数
   `radiusKm=5.0`）。模拟成功 GEO 查询返回 `7104`（`0.05km`）、`7102`（`0.4km`）、
   `7105`（`0.08km`）、`7103`（`0.1km`）；`7101` 不在附近结果中。返回次序刻意与距离次序不同，
   避免数据库 ID 顺序或 mock 返回顺序掩盖排序问题。
3. 携带真实骑手 JWT 请求 `GET /rider/delivery-tasks/available?radiusKm=5.0`。
   断言 HTTP `200`、业务 `code=200`、`message=success`，`data` 为数组且长度为 `3`，
   次序为 `7103, 7102, 7101`，验证附近优先、附近任务按距离升序、远处任务仍然可见，
   且 GEO 中残留的已领取和已完成任务会被 MySQL 状态过滤。
4. 将同一个 GEO 查询改为抛出
   `new RedisGeoOperationException("simulated-delivery-geo-failure")`，再次发送相同请求。
5. 断言第二次响应仍为 HTTP `200`、业务 `code=200`、`message=success`，
   `data` 长度为 `3`，ID 集合精确等于 `{7101, 7102, 7103}`，没有重复。
   降级结果不要求保留距离排序；当前实现按任务 ID 从新到旧返回，最多 `500` 条。
6. 再模拟骑手位置 GEO 查询抛出相同异常，断言大厅仍成功返回这三条 MySQL 任务，且不会尝试配送任务 GEO 查询。
7. 对两个异常路径均断言 MVC 没有未处理异常，响应正文不含异常类名、模拟故障消息或堆栈信息。
   核实 GEO 查询确实调用两次、MySQL 任务的 ID/状态/归属没有改变。

另一个集成用例传入 `radiusKm=2.0`，断言该半径被原样传给
`DeliveryTaskGeoService.findNearbyTasks`，且返回顺序仍为附近优先。

## 当前实现与边界

- 删除原分页方法和 `PageInfo` 响应；`GET /rider/delivery-tasks/available` 改为接收 `radiusKm`，
  缺省 `5` 公里，返回附近优先排序的完整任务列表，不再分页。
- 距离在 `DeliveryTaskDistanceVO` 中保存为千米数值，避免对带 `km` 的展示字符串执行 `Double.parseDouble`。
- RiderLocation 或任务 GEO 查询失败、附近结果为空或附近结果全部损坏时，按任务 ID 从新到旧返回，最多 `500` 条；该上限由 MySQL 查询直接执行。
  [DeliveryTaskHallServiceTest](../../src/test/java/org/example/takeout/DeliveryTask/Service/DeliveryTaskHallServiceTest.java)
  覆盖近距离优先、距离相同按 ID、两处 GEO 降级、损坏成员过滤、SQL 500 条上限和非法半径。
- 若骑手 Redis 位置查询本身失败，服务没有位置中心，因此任务附近查询不会执行；本实现仍返回普通任务列表。骑手位置为空（尚未上传）时仍要求先上传位置。
  GEO 只参与排序，是否可接仍由 MySQL 的状态和骑手归属决定。
- 接口拒绝零、负数以及非有限查询半径，例如 `NaN` 和正负无穷。
- 正常附近排序会读取全部 MySQL 可接任务后在内存排序；Redis GEO 最多给出半径内最近的 100 个候选，这些任务按距离升序排在前面，其他可接任务按 ID 升序排在后面。降级查询在 SQL 中按 ID 倒序并限制 500 条。实验没有验证超大任务池下正常排序的性能。
- 出餐事务提交后会按 MySQL 任务快照修复该任务 GEO；抢单事务提交后移除 GEO 成员。定时全量对账每轮用一条 MySQL 查询读取全部可接任务，并用 Redis ZSET `range(0, -1)` 读取全部成员，仅逐单处理集合差异。单任务修复使用 `REQUIRES_NEW`、`REPEATABLE_READ` 和 `FOR UPDATE` 持锁完成 Redis 操作；成员同时存在于两侧时不会重查坐标，因此坐标漂移不会在这类全量对账中校正。
- 骑手位置服务通过 `PUT/GET/DELETE /rider/location` 读写本人位置。位置缺失仍要求先上报；Redis 位置读取失败时直接走 MySQL 降级列表，不再查询任务 GEO。

两类 Redis 故障都会在服务端记录降级警告和异常堆栈；客户端响应仍为成功结果。
