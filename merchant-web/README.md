# Tokeout 三端前端

目录名沿用 `merchant-web`，当前已经包含顾客、商家和骑手三端。Vue 3 + Vite + Vue Router + Element Plus + Axios；仅调用现有后端，未调整 Java、SQL 或后端配置。页面结构、接口映射和故障边界见 [FRONTEND.md](FRONTEND.md)，本轮实际验证见 [QA-RESULTS.md](test/QA-RESULTS.md)。

## 本地运行

在此目录使用 Node.js 22+：

```powershell
npm ci
npm run dev
```

默认打开 `http://localhost:5173/#/`。后端需运行在 `http://localhost:8080`；开发和预览服务统一代理 `/api`、`/user`、`/cart`、`/order`、`/merchant`、`/rider`、`/category`、`/uploads`、`/images`。如后端位于其他地址，在启动 Vite 前设置 `TAKEOUT_API_TARGET`。浏览器始终请求前端来源，无需修改后端 CORS。

入口：

- 顾客：`/#/customer/shops`，可匿名浏览，加入购物车前登录。
- 商家：`/#/merchant/orders`，支持订单履约与新单提醒；导航可进入商品、分类和店铺设置。
- 骑手：`/#/rider/tasks`，支持定位、任务大厅、抢单、当前配送与送达。

三端使用各自账号，可从登录页进入注册。不同角色在同一标签页中可分别保留会话，退出仅清除当前角色。密码不保存。顾客导航提供浏览历史和账户入口。

尚未接入地图选址时，顾客结算页可手动填写收货经纬度，并可点击“填入杭州示例坐标”快速测试。示例为经度 `120.1536`、纬度 `30.2812`，仅用于测试；实际配送应填写与收货地址对应的坐标。坐标随下单请求保存，结果未确认时不能改动；旧版缺少坐标的待确认请求，应先核对订单，再通过结算页现有入口恢复填写。

## 构建与验证

```powershell
npm test
npm run build
npm run preview
```

本机 esbuild 若遇到 Windows 临时目录权限问题：

```powershell
$env:TEMP = Join-Path $PWD '.tmp'
$env:TMP = $env:TEMP
npm run build
```

`test/api-fixture.mjs` 是独立内存接口测试服务，只用于前端 QA，默认不启动，不连接真实数据库。手动运行时：

```powershell
# 终端一
$env:TAKEOUT_UI_QA_FIXTURE = '1'
node test/api-fixture.mjs

# 终端二
$env:TAKEOUT_API_TARGET = 'http://127.0.0.1:18080'
npm run dev
```

测试替身只验证界面交互和请求契约，不能代替真实 MySQL/Redis、网络故障或设备定位联调。正式开发不设置 `TAKEOUT_UI_QA_FIXTURE`，默认仍连接 8080 后端。

## 当前范围

已实现阶段 1–5：公共入口/路由/角色会话和错误处理；顾客浏览、购物车、结算、模拟支付、订单查询与确认；商家商品/分类/店铺管理、订单履约和新单提醒；骑手任务与配送；三端注册、账户入口和顾客浏览历史。支付明确是模拟支付。

店铺资料初次仅部分回填；电话和坐标需主动修改。商品恢复仅覆盖当前前端会话中已知的删除记录。浏览历史记录不含商家信息。真实支付、优惠券、评价、地址簿、平台后台、骑手收入和轨迹无对应接口，未加入。

骑手定位复用 `../src/main/resources/static/rider-web/` 的原生模块，原页面继续保留。定位需主动授权；手机访问电脑局域网 HTTP 通常不能使用定位，应使用受信任的同源 HTTPS。当前配送任务无需定位即可查看和完成。

## 部署

Hash 路由不要求服务器配置深层页面回退。生产环境独立托管 `dist/`，以同源反向代理转发 API 和图片；Vite 的开发代理不属于生产构建产物。原后端静态白名单没有开放本前端，不要直接把 `dist/` 拷贝进 Spring 静态目录后假定可匿名访问。

`/merchant/events` 的反向代理需要透传 Authorization、允许长连接并关闭响应缓冲，才能及时送达 SSE 提醒。断线期间仍会通过未查看订单查询补偿。
