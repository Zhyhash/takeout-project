# 前端阶段 1–5 实施说明

## 页面结构与交互

三端共用 Vue 3 应用、字体色彩、中文 Element Plus、统一响应错误处理；按角色分离会话和接口客户端。页面使用 Hash 路由，不占用后端的 `/merchant/**`、`/rider/**` 等 API 路径，也不要求后端新增 SPA 路由。

```mermaid
flowchart LR
  Entry[角色入口] --> Shops[顾客：店铺列表与菜单]
  Shops --> Login[加购前登录]
  Login --> Cart[购物车]
  Cart --> Checkout[收货信息与结算]
  Checkout --> CustomerOrder[订单详情与模拟支付]
  CustomerOrder --> Merchant[商家：接单或拒单]
  Merchant --> Ready[制作完成与出餐]
  Ready --> Rider[骑手：抢单与当前配送]
  Rider --> Delivered[确认送达]
  Delivered --> Confirm[顾客确认收货]
```

顾客列表采用搜索栏、店铺卡片和分页；菜单采用分类索引、商品列表、加购和底部购物车入口。购物车及结算在桌面上分为商品/表单与摘要两栏，手机上纵向排列。订单详情展示当前业务状态、商品明细、收件信息和状态允许的操作。

商家保留现有订单工作台和详情抽屉；待接单提供接单与拒单，制作中提供出餐；后续订单包含已出餐、配送中、送达和终态订单。拒单弹窗明确说明取消及模拟退款。

骑手采用定位状态卡、可接任务/正在配送切换、任务卡与详情抽屉。当前配送不依赖定位权限。已有原生定位模块直接通过 Vite alias 复用，不复制或重写其上报与清理时序。

## 路由与接口

| 页面 | Hash 路由 | 主要接口 |
| --- | --- | --- |
| 三端登录 | `/:role/login` | `POST /user/login`、`/merchant/login`、`/rider/login` |
| 店铺列表 | `/customer/shops` | `GET /api/customer/shops` |
| 店铺菜单 | `/customer/shops/:shopId` | `GET /api/customer/shops/{shopId}` |
| 购物车 | `/customer/cart` | `GET/POST/PATCH/DELETE /cart/items`、`DELETE /cart/items/all` |
| 结算 | `/customer/checkout` | `POST /order`、`PATCH /order/{id}/pay` |
| 顾客订单 | `/customer/orders`、`/customer/orders/:orderId` | `GET /order`、`GET /order/{id}`、取消/支付/确认接口 |
| 商家订单 | `/merchant/orders` | `/merchant/orders` 列表、详情、`accept/reject/ready` |
| 骑手工作台 | `/rider/tasks` | `/rider/location`、`/rider/delivery-tasks/available/current/{id}/claim/complete` |
| 三端注册 | `/:role/register` | `POST /user/register`、`/merchant/register`、`/rider/register` |
| 当前账号 | `/:role/account` | 本地登录身份，退出先执行相应定位清理 |
| 顾客浏览记录 | `/customer/history` | `GET/DELETE /user/browse-history`；显式打开商品时 `POST /user/browse-history/{id}` |
| 商品与分类 | `/merchant/products`、`/merchant/categories` | `/category/products`、商品编辑/图片/上下架、`/merchant/restore/{id}`、`/category` |
| 店铺设置 | `/merchant/settings` | 公开店铺详情部分回填；`PUT/PATCH /merchant/info` |
| 商家新单提醒 | 所有登录后的商家页面共用 | `GET /merchant/events`、`GET /merchant/orders/unseen`、`PATCH /merchant/orders/seen` |

## 数据与故障边界

- 顾客可匿名浏览；购物车和订单要求顾客会话。三端使用独立的 sessionStorage key，密码不存储，Token 不进入 URL。
- HTTP 200 不表示业务成功。统一读取响应体 `code`；401 只清除原请求所属角色且匹配原 Token 的会话。403 保留会话。旧会话请求不能清除新登录状态。
- 店铺营业、商品在售的实际代码均为 `0`。店铺搜索仅提供后端支持的名称、状态和分页条件。
- 购物车数量变更按 `quantityChange = ±1` 提交；跨店加购必须确认清空，无法原子完成的清空与加购分别展示结果。`canBuy` 不满足时不能提交全车结算。
- 一次下单先持久化请求标识与固定收货参数，再发请求；未知结果的重试保持同一标识。订单创建与模拟支付是两个独立操作。客户端金额只作展示，结算金额由后端确定。
- 收货地点坐标可手动填写，以数值 `longitude` / `latitude` 随订单提交。经度范围 [-180,180]，纬度范围 [-90,90]；示例填充仅用于测试，不代表设备定位。坐标与收货参数一起冻结，已有请求不能在沿用请求标识时追加或改动坐标。
- 状态操作遵循后端：顾客仅待支付可支付或取消，仅已送达可确认收货；骑手抢单即开始配送，没有独立取餐步骤；送达不等于顾客订单完成。
- 骑手位置需要明确授权及安全上下文。停止、页面隐藏、路由离开、退出时先停止监听，再等待已发出的上报结束并清理服务端位置；删除失败不能宣称已经删除。
- 普通请求超时可能已经被服务端执行；操作失败后查询权威状态，避免盲目重复提交。定位上报保留原模块不通过超时中断 PUT 的策略。

## 验收范围

检查构建、角色会话隔离、下单意图恢复和重复提交保护；使用测试服务核对页面请求和状态流转，再在真实后端环境验证数据库、Redis 与设备定位。测试替身不构成真实支付、配送或 GPS 证明。

阶段 4–5 补齐商品、分类、店铺设置、新单提醒、三端注册、账号入口和顾客浏览记录。地址簿、优惠券、评价、平台后台、收入与轨迹无现成接口，未添加占位功能。

## 阶段 4–5 的执行约束

- 商品编辑先读取专用编辑详情，携带版本号且只提交改动字段。库存必须主动选择重设并确认，普通资料编辑不提交库存。编辑详情没有分类 ID，因此不按名称猜 ID；只有主动选择新分类才变更分类。
- 商品创建和编辑的价格、库存规则不同，以各自 DTO 为准。图片以 multipart 的 `file` 上传，客户端预检 JPEG/PNG 和 5 MB；服务端继续负责真实内容验证。
- 商品列表不包含逻辑删除记录，因此恢复区只保存当前前端会话里成功删除的商品，不能当作持久化回收站。分类只有创建、读取和删除接口，没有改名功能；删除普通分类后商品转入默认分类。
- 公开店铺详情可回填名称、地址、图片、简介、营业时段和状态，不能读取手机号或经纬度。未知值不以空白覆盖，需勾选主动修改；资料保存只在打烊后允许，营业状态单独确认。
- SSE 使用 fetch 流读取与 Authorization 请求头，不使用 URL Token。建立或重连、重新显示页面时补查未查看订单，页面可见时每30秒补查；提示异常时订单列表仍可独立刷新。
- NEW 只用于尚未查看的待接单订单。卡片标记至少一半进入可见区域并停留1.5秒，且标签页可见后，才批量确认指定 ID；失败保留标记并重试。不把收到事件等同于订单已被展示。
- 支持 Web Locks 的浏览器中，同商家多标签页只允许一页占用实时连接，其他页面保留定时同步；不支持 Web Locks 时仍受现有后端单连接限制。提醒不提供持久化消息或跨实例广播保证。
- 三角色注册按各自字段提交并回到登录页，密码不持久化、不自动登录。浏览记录仅在显式打开商品详情时尝试保存；历史记录不含商家 ID，不能据此虚构店铺详情或导航。
