# 骑手 Web 真实定位接入

2026-10-28 按当前工作区实现。仓库此前只有 Spring Boot 后端，没有前端框架、页面、请求封装或前端 Token 存储；本次经确认新增同源原生 HTML/CSS/JavaScript 页面，不引入 npm 依赖、地图 SDK 或新的工作状态模型。

## 入口与文件

按[运行指南](project-guide.md#6-启动方式)启动后打开 `http://localhost:8080/rider-web/index.html`。页面与 API 共用 Spring Boot 的来源和 context path，无需独立前端服务或 CORS 配置。静态页及五个依赖文件仅对 GET/HEAD 公开，骑手 API 继续执行原 JWT 和账号有效性检查。

| 文件 | 职责 |
| --- | --- |
| [index.html](../src/main/resources/static/rider-web/index.html)、[styles.css](../src/main/resources/static/rider-web/styles.css) | 登录表单、定位控制、位置与任务列表，含窄屏布局 |
| [app.mjs](../src/main/resources/static/rider-web/app.mjs) | 页面事件、标签页会话、状态展示及页面隐藏/离开时暂停 |
| [api.mjs](../src/main/resources/static/rider-web/api.mjs) | 同源 fetch、Bearer 头、统一响应解包与错误分类 |
| [location-tracker.mjs](../src/main/resources/static/rider-web/location-tracker.mjs) | Geolocation 生命周期、节流、串行上报及停止后删除 |
| [distance-display.mjs](../src/main/resources/static/rider-web/distance-display.mjs) | 取货距离的米、公里和未知值展示 |
| [AuthPathMatcher](../src/main/java/org/example/takeout/Common/Auth/AuthPathMatcher.java) | 仅放行六个确切静态文件的读请求 |
| [RiderLocationService](../src/main/java/org/example/takeout/Rider/Service/RiderLocationService.java) | 经确认仅修正活跃时间查询的 member 为字符串；保留既有位置、过期和清理机制 |
| [rider-api.test.mjs](../src/test/js/rider-api.test.mjs)、[rider-location-tracker.test.mjs](../src/test/js/rider-location-tracker.test.mjs)、[rider-distance-display.test.mjs](../src/test/js/rider-distance-display.test.mjs) | Node 内置测试：API 契约、错误分支、节流、并发启停和取货距离展示 |
| [RiderWebAssetsApiTest](../src/test/java/org/example/takeout/api/RiderWebAssetsApiTest.java)、[RiderLocationServiceTest](../src/test/java/org/example/takeout/Rider/Service/RiderLocationServiceTest.java) | 静态资源和 API 鉴权边界、位置读取的字符串成员及过期分支 |

## 真实接口与鉴权

所有业务响应都是 `{code, message, data}`，成功业务码为 `200`；HTTP 200 不表示业务一定成功。

- `POST /rider/login`：请求 `{name,password}`，成功 `data={id,name,token}`。新页面把该结果保存在当前标签页的 `sessionStorage`；存储不可用时只在内存保留。密码不写入存储，Token 不放进 URL。
- `PUT /rider/location`：请求 `{longitude,latitude}` 两个 JSON 数值，不传 riderId；从 Bearer JWT 取得当前骑手身份。
- `GET /rider/location`：成功返回坐标对象或 `null`。保留在 API 封装中；页面开始定位时始终向浏览器获取新坐标，不把既有服务端位置当作新的浏览器定位。
- `DELETE /rider/location`：无请求体，清除当前骑手的位置和活跃记录。
- `GET /rider/delivery-tasks/available?radiusKm=5`：返回任务数组，不是 PageInfo。每项包含 `taskId,merchantName,merchantAddress,receiverAddress,deliveryReward,status,statusDesc,createTime,pickupDistanceKm`；`pickupDistanceKm` 是骑手最近上报位置到商家取货点的 GEO 距离（公里），仅 GEO 命中的附近任务有值，未命中或降级列表中的任务为 `null`。

位置和任务接口都带 `Authorization: Bearer <token>`。页面保持服务端返回顺序，按 `pickupDistanceKm` 展示取货距离：小于 1 公里以米显示，1 公里及以上以公里显示，`null` 或缺失值显示“取货距离未知”；不在前端计算地理距离或重排任务。后端正常 GEO 排序和 MySQL 降级都可能返回成功数组，现有响应没有降级标记，因此页面不能精确判断某次成功列表是否采用 GEO 排序。

## 生命周期与调用链

登录本身不会触发定位权限。点击“开始定位”后检查安全上下文和 Geolocation 支持，再以 `enableHighAccuracy:true, maximumAge:0, timeout:15000` 启动 `watchPosition`。只有有效、未过时、满足后端范围的真实坐标才会进入上报队列；不使用默认坐标。

```text
浏览器 GeolocationPosition
  -> api.updateLocation({longitude,latitude})
  -> PUT /rider/location + Bearer JWT
  -> RiderLocationController -> RiderLocationService.updateLocation
  -> 既有 Lua：GEOADD rider:locations + ZADD rider:last_active
  -> PUT 业务成功
  -> GET /rider/delivery-tasks/available?radiusKm=...
  -> 后端读取骑手位置、任务 GEO，使用 MySQL 任务状态过滤/降级
  -> 页面按返回顺序显示任务
```

最短上报间隔是 30 秒，包括失败尝试；同时最多一个 PUT，在途时只保留最新一份位置，不积压中间坐标。超过 60 秒的定位结果不再提交。若设备静止、watch 没有产生新回调，每 30 秒检查并通过 `getCurrentPosition(maximumAge:0)` 请求新的真实位置，不反复提交旧坐标续活。

停止时立即置为非活跃、递增客户端代次、执行 `clearWatch`、清除定时器及待发送坐标；等待已发出的 PUT 结束，再 DELETE。旧定位回调及旧任务响应不能重新上报或回填列表。DELETE 完成前禁止重新开始；失败后可重试删除。退出登录先走同一清理过程，凭证失效无法清理时允许退出本地会话并明确提示未确认删除。

页面隐藏或 `pagehide` 会停止定位并尽力用 `keepalive` 删除位置，返回页面后需要主动重新开始。没有 Service Worker、后台保活、自动派单、轨迹或导航。当前后端只有骑手账号正常/禁用状态；页面“停止定位”不改变账号状态、既有配送任务或直接抢单接口的资格。

## 错误与浏览器限制

页面分别展示不支持定位、安全上下文不足、拒绝权限、无法获取位置、定位超时、位置上报失败、后端位置服务异常、任务查询失败及删除失败。权限拒绝停止监听；超时或暂时不可用保留恢复机会，等待新的真实位置。上报失败不加载任务，任务查询失败可以单独刷新。`code=5000` 作为位置服务异常展示，`401/403` 提示重新登录；网络或响应格式问题不使整个页面崩溃。

Geolocation 需要用户授权及安全上下文，站点的 Permissions-Policy 也可能阻止定位。[MDN watchPosition](https://developer.mozilla.org/en-US/docs/Web/API/Geolocation/watchPosition)

- 桌面本机 `http://localhost:8080`、`http://127.0.0.1:8080` 通常可用于定位开发，仍受设备定位服务和浏览器权限影响。
- 手机访问 `http://192.168.x.x:8080` 这样的电脑局域网 HTTP 地址通常不是安全上下文，页面会提示不可用；手机上的 localhost 指手机自身，不能直接替代电脑 IP。
- 手机实测可在本地另行配置设备信任证书的 HTTPS 入口，并把页面和 API 一起同源代理到 Spring Boot。本文仅说明方案，没有修改 Compose、TLS、端口或部署架构，也没有加入浏览器安全开关绕过。

上述差别来自安全上下文的 loopback/HTTPS 规则。[MDN Secure Contexts](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Secure_Contexts)

## 验证与明确边界

```powershell
node --test src/test/js/rider-api.test.mjs src/test/js/rider-location-tracker.test.mjs src/test/js/rider-distance-display.test.mjs
mvn -o "-Dtest=DeliveryTaskHallServiceTest,DeliveryTaskGeoServiceTest,DeliveryTaskServiceTest,DeliveryTaskApiTest,RiderWebAssetsApiTest,JwtInterceptorTest,StatusDescriptionMappingTest" package
```

2026-10-29 距离链审计后：39 项 JavaScript 测试、104 项 Java 定向测试及 Maven 打包通过，均无失败、错误或跳过。本机 Maven 测试额外通过 `argLine` 加载 Mockito Java agent，并设置 `jdk.net.URLClassPath.disableClassPathURLCheck=true`。没有新增前端测试依赖或构建工具；原生模块随 Spring Boot 打包。

实际浏览器仅完成静态页面加载、必填校验、使用虚拟测试输入展示登录失败提示和布局检查。预览服务没有连接业务 API，没有授权或验证真实 GPS，没有运行真实 MySQL/Redis 联调；自动化中的位置和 API 是测试替身，不构成设备定位证明。

关闭标签页、浏览器被杀或网络中断时，DELETE 不保证送达；已经发送的 DELETE 也不能在页面退出时追溯改成 keepalive。客户端不会通过中止 PUT 来声称服务器写入已取消。无法确认清理时仍依赖既有 5 分钟位置过期与定时清理；多标签页/多设备使用同一骑手会共享同一个后端位置，这次没有引入跨页面 session fencing。浏览器位置可能来自 Wi-Fi、基站或其他设备定位来源，精度由浏览器报告，不保证来自卫星 GPS。
