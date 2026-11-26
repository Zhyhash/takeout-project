# 三端前端 Stripe 支付与退款实施计划

审查基线为 2026-11-21 的工作区源码。首个交付目标是让顾客从已有订单完成一笔 **Stripe 沙盒支付**，在关页、验证回跳或网络超时后仍能找回同一笔订单，并让顾客和商家看到后端实际记录的退款进度。以下文件改动和测试均为后续实施计划。本次只做了代码、测试和接口文档审查。

## 一、接口契约与当前缺口

前端使用 Vue 3、Vue Router hash 路由及按角色创建的 Axios 客户端。业务接口返回 `{code, message, data}`，`src/api/http.js` 仅在数值 `code === 200` 时返回 `data`。下表的路径均为应用接口，Vite 已代理 `/order` 和 `/merchant`。支付请求沿用 `createRoleHttp('customer')` 和顾客 Bearer token。

| 前端动作 | 已核实的后端契约 | 当前差距与处理 |
| --- | --- | --- |
| 提交订单 | `POST /order` 接收 `requestId`、收货人、电话、地址、经纬度、可选备注，返回 `{orderId, orderNo}` | `CheckoutView.vue` 已在发请求前按顾客保存固定载荷，成功后记录 ID 并进入详情。仅调整“模拟支付”提示，支付恢复从已有 ID 开始。 |
| 读顾客订单 | `GET /order?pageNum=…&pageSize=…` 返回 `PageInfo.list/total`，`GET /order/{id}` 返回详情。两者均有 `id/status/statusDesc/totalAmount` | 列表和详情可直接展示退款。详情的旧错误分支用“排除 0、1、4”推断支付完成，会把 9、10 和未来状态算作支付成功。 |
| 创建或续付 | `POST /order/{id}/payment`，仅正整数订单 ID，无请求体。服务端按订单总额创建或复用 PaymentIntent | `customerApi.payOrder` 和顾客详情仍调用 `PATCH /order/{id}/pay`。开启 Stripe 后旧入口明确返回业务错误。支付按钮应切换到新接口。 |
| 读取支付记录 | `GET /order/{id}/payment` 只读本地。即使没有支付记录也可成功返回订单状态 | Vue 尚无此调用。空记录、未绑定远端 ID 和处理中需要分别处理。 |
| 主动同步 | `POST /order/{id}/payment/sync`，无请求体。已有本地记录时尝试恢复，返回本地最新支付视图。无记录时报业务错误 | Vue 尚无此调用。同步与后台恢复共享退避，同步成功返回也可能与上次状态相同。 |
| 取消、收货 | `PATCH /order/{id}/cancel` 仅允许 0，`PATCH /order/{id}/confirm` 仅允许 8 | 保留原入口。在创建支付、客户端确认或结果未明时锁住冲突操作。状态 1 没有顾客取消接口。 |
| 商家拒单与查询 | `PATCH /merchant/orders/{orderId}/reject` 返回 `Result.success("success")`。详情 `GET /merchant/orders/{orderId}` 返回 `orderId/status/statusDesc/payTime/totalAmount` 等 | 列表、抽屉均写着“模拟退款”。拒单响应没有退款结果，须按商家身份重读列表和该笔详情。顾客、商家均无单独的退款查询、创建或重试接口。 |

**支付响应。** 三个支付接口共用 `StripePaymentView`，成功响应均设 `Cache-Control: no-store`。字段为 `orderId`、`paymentIntentId`、`clientSecret`、`publishableKey`、`amountMinor`、`currency`、`paymentStatus`、`orderStatus`。订单金额 `totalAmount` 单位为元，支付金额 `amountMinor` 为整数分，当前币种为 `cny`。金额由后端计算，前端仅格式化显示。无支付记录时 ID、分金额和支付状态均可为空。已经建立本地记录但尚未绑定远端时，分金额可以已有值。可空字段兼容缺失和 `null`。订单状态是数字，支付状态是 Stripe 字符串，两者分别判断。

**确认凭证。** 只有创建或复用接口可能交付可用的 `clientSecret`。GET 和 sync 始终不给确认凭证。POST 即使返回 200，在并发占用、退避、支付窗口届满、支付已结束或进入人工复核时也可能没有凭证。响应不暴露 `reconciliationState`、下次可重试时间或复核原因，不能从无凭证反推“被拒付”或“已转人工”。创建接口的 Controller mock 曾使用 `orderStatus=0` 的样例，真实服务领取支付资格后处于 1，页面按服务实现和事务测试判断可操作性。

**运行模式。** `payment.stripe.enabled` 默认关闭，关闭时新支付 Controller 未注册，商家拒单也会被拒绝。开启时只接受测试密钥并禁用旧模拟支付。建议新版顾客入口面向明确启用 Stripe 的沙盒，遇到未启用、代理异常或响应无法识别时保留订单和查询入口，提示“支付服务暂不可用”。目前没有公开的支付模式探测接口。404、业务 500、503 或超时都不触发旧 `/pay`。

**前端基线。** 三角色令牌分别保存在各自的 `sessionStorage` 键，下单记录则按顾客 ID 写入 `localStorage`。项目尚无 Stripe.js 依赖。现有 QA fixture 通过旧 `/pay` 将 0 直接改成 2，通过拒单将 2 直接改成 4，均跳过了中间状态。它适合沿用为离线测试服务，现有通过记录不能说明 Vue 已验证 Stripe。[顾客 API](../merchant-web/src/api/customerApi.js) · [下单记录](../merchant-web/src/customer/checkoutIntent.js) · [角色会话](../merchant-web/src/auth.js) · [QA fixture](../merchant-web/test/api-fixture.mjs)

后端依据见 [订单入口](../src/main/java/org/example/takeout/Order/Controller/OrderController.java)、[支付入口](../src/main/java/org/example/takeout/Payment/Controller/StripePaymentController.java)、[支付视图](../src/main/java/org/example/takeout/Payment/VO/StripePaymentView.java)、[支付编排及凭证下发](../src/main/java/org/example/takeout/Payment/Service/StripePaymentService.java)、[订单状态流转](../src/main/java/org/example/takeout/Order/Service/OrderService.java) 和 [测试模式校验](../src/main/java/org/example/takeout/Payment/Config/StripePaymentConfiguration.java)。

## 二、顾客从待支付到可恢复的确认流程

1. **打开订单。** 先用路由的订单 ID 读 `GET /order/{id}`，确认当前顾客可见。状态 0 显示“去支付”和原取消入口，进入列表、打开详情、刷新均不触发创建。状态 1 或支付回跳时读取本地支付视图。已进入履约、取消或退款的订单直接按详情展示。支付读取失败只影响支付区域。下单过程中断且尚无订单 ID 时，继续走结算页现有“原请求重试／核对订单列表”，不凭相同金额或最近一单认领订单。
2. **领取支付资格。** 顾客点“去支付”后先加提交锁，再 POST 同一订单的 `/payment`。服务端在短事务内竞争 `0 → 1`，落同一订单的支付记录与稳定幂等键，再到事务外创建或取回 Intent。前端不传金额、币种、支付结果或 Stripe 幂等键。响应到达时核对订单、顾客会话和操作轮次。仅在 `orderStatus=1`、ID 匹配且有可用公钥、凭证、可操作支付状态时开放支付操作。无凭证转查询区。确认页用服务端分金额展示“确认支付 ¥…”，金额缺失、币种异常或与详情不符时停止确认并刷新核对。
3. **客户端确认。** 在顾客详情的付款区域按需加载 `@stripe/stripe-js`，用响应中的 `publishableKey` 初始化，调用 `stripe.elements({clientSecret})`，再用 `elements.create('payment')` 创建并挂载表单。等组件就绪并由顾客提交后，调用 `stripe.confirmPayment({elements, confirmParams: {return_url}, redirect: 'if_required'})`。输入数据交给 Stripe iframe。3DS 挑战或银行跳转由 SDK 驱动。确认期间锁按钮、标记正在验证。SDK 加载失败可在原订单重试加载。校验错误可原位更正，卡片拒绝或认证被关闭后回读当前状态，再决定能否用同一 Intent 重试。[Stripe.js 确认参数与回跳行为](https://docs.stripe.com/js/payment_intents/confirm_payment)
4. **确认收款。** SDK 就地返回或外部验证回跳都进入“正在核对支付结果”。已有本地支付记录且结果有待核实时，单次调用 `/payment/sync`，再读订单详情。Stripe 自行向服务端的 `POST /webhooks/stripe` 投递签名事件，事件可能先于这两个调用完成，顾客关页后服务端也能处理。页面以服务端订单状态为准。2 表示已支付待接单，3、5–8 展示实际进度，4/9/10 使用取消或退款文案。快照与详情暂不一致时关闭支付操作、重读详情。商家新单通知由服务端在收款事务提交后发送。[Webhook 入口](../src/main/java/org/example/takeout/Payment/Controller/StripeWebhookController.java) · [支付事务与事件处理](../src/main/java/org/example/takeout/Payment/Service/StripePaymentTransactionService.java) · [Stripe 支付状态与 Webhook](https://docs.stripe.com/payments/payment-intents/verifying-status)

### 按服务端视图决定下一步

| 最近观测 | 顾客可做的事 |
| --- | --- |
| 0，尚无支付记录 | 主动开始支付，或取消订单。若刚有创建超时，先重读详情和本地视图，防止在未知结果上重复操作。 |
| 1，`requires_payment_method` / `requires_confirmation` | 可主动继续支付。页面内已有匹配凭证且没有结果未明的提交时复用当前表单，普通 GET 缺少凭证不影响它。刷新丢失表单后，点“继续支付”再 POST 原订单 `/payment`。 |
| 1，`requires_action` | 告知“需要完成支付验证”。进行中的确认由 Stripe.js 继续处理。认证途中刷新后，顾客点“继续验证”才重新向原订单领取凭证并核实同一 Intent 的当前状态。只续接所选支付方式支持的下一步认证。中断续接须单列沙盒验证，无法续接时保留查询入口。 |
| 1，`processing` / `requires_capture` | 提示“支付处理中”或“等待支付结果”，只读或有限同步。页面不开放再次扣款或捕获款项。 |
| 1，状态为空、未见记录、无凭证或未知状态 | 已知分金额但远端 ID 为空表示本地已有记录，可稍后核对绑定结果。金额、ID、支付状态均为空时先重读订单与本地视图。若 1 持续缺记录，保留订单号供核查，停止创建和同步。未知状态只开放查询。已绑定且状态可操作时可稍后由顾客续付，同一订单上恢复，避免循环 POST。 |
| `succeeded` / `canceled` | 退出表单，回读订单，以本地订单已确认的 2 或后继状态、4 等展示。若仍为 1 则继续核对。退款后的原支付仍可能是 `succeeded`。 |

### 刷新、回跳和并发

- **普通刷新**只凭顾客会话与订单 ID 恢复。关闭表单、浏览器后退或卸载组件都不会撤销已取得的支付资格。对状态 1，先读本地。如果要补凭证，让“继续支付”直接使用创建或复用接口。每次续付前都先 sync 会消耗与创建共享的恢复机会，随后的 POST 可能再次拿不到凭证。
- **回跳地址**建议使用同源 SPA 的实际入口路径（兼容子目录部署）和自有查询参数 `?payment_return_order=<订单ID>`。在付款前生成固定地址。Stripe 可能在真实 URL 的 search 附加 `payment_intent`、`payment_intent_client_secret`、`redirect_status`，hash 路由只看 `route.query` 会漏读。入口在路由、统计和第三方脚本运行前识别回跳，仅取合法正整数订单 ID，用 `history.replaceState` 一次换成干净的 `#/customer/orders/<id>`，再走现有鉴权。用一次性非敏感回跳标记触发核对。参数损坏回顾客订单列表。未登录只向顾客登录页传干净的站内目标。回跳参数均不作为支付或订单归属凭据。正常三端深链、列表核对参数不受这段处理影响。
- **敏感数据**只在当前支付组件内存中短暂持有。订单的 `checkoutIntent`、浏览器存储、业务请求体、日志、埋点、报错和测试截图均不保存凭证、卡数据或完整回跳 URL。JWT 只发给本项目相应角色的 API。入口采用 `no-referrer`，部署联调核查回跳请求查询串的访问日志脱敏。后端 `rk_test_` 和 Webhook 签名密钥只留服务端。独立测试页可参考其清理顺序，Vue 仍使用自己的会话与路由。[项目测试页](../src/main/resources/static/stripe-test.html) · [Stripe 客户端凭证说明](https://docs.stripe.com/payments/payment-intents#passing-the-client-secret-to-the-client-side)
- **有限同步。** 确认、回跳或用户明确点“核对支付结果”时才尝试主动 sync。建议可见详情在结果未知时按 2、5、10、20 秒各做一次本地 GET，每次完成后再安排下次。仍未定则显示“稍后刷新此订单”，保留人工刷新。这些间隔是前端建议值。支付窗口从订单创建起算 30 分钟。超时与确认竞争时，服务端依据 Stripe 的可信终态处理已在支付中的订单，页面倒计时不能裁决取消或付款。普通轮询只读本地，遇到状态变化回读订单详情。确认结果不明时不自动重提。退款轮询仅读订单详情。
- **同一操作只执行一次。** 支付、取消、确认、手动刷新协调互斥，晚到的 GET 不覆盖新结果。现有详情在同名路由间会复用组件。支付相关回调、定时器和 `finally` 均绑定订单 ID、顾客 token 和当前轮次。请求支持 `expectedToken`。切订单、退出、换号或卸载时使旧回调失效，销毁 Element、清除凭证及定时器。页面隐藏暂停轮询，可见时重新读本地状态。不假设本标签页的锁能约束另一标签页，跨页竞争由后端幂等键和状态条件更新裁决。
- **异常分流。** HTTP 200 中的业务错误也走现有拦截器。401 只清理发起请求的顾客会话，403 保留登录并提示权限问题。不存在或非本人订单停止读取。过期、并发取消、订单状态变化先读详情。503、超时、离线或无法识别的响应提示“结果未确认”，保留同一 ID。已知卡片输入错误与结果不明的网络故障应给不同的操作入口。客户端关闭认证、SDK 返回成功或失败，都不足以在本地改写订单。

## 三、退款如何出现在三端

商家拒绝待接单订单会触发退款。支付后满 10 分钟仍未接单时，后台任务也会按条件更新抢占退款资格。两者都要求状态为 2 且有关联的成功 Stripe 支付。服务端在事务内将订单改为 9、按成功支付的全额分值准备退款并归还库存，提交后异步请求 Stripe。只有核实退款成功才推进到 10。退款请求失败、待恢复或转人工时，订单可能长期显示 9。最新一次退款在成功后又收到可信失败结果，后端还可以把 10 调回 9 复核。刷新要接受这种真实变化。[拒单与退库](../src/main/java/org/example/takeout/Order/Service/OrderCommandService.java) · [退款准备及状态回写](../src/main/java/org/example/takeout/Payment/Service/StripeRefundTransactionService.java) · [超时、接单竞争与失败测试](../src/test/java/org/example/takeout/integration/OrderAcceptTimeoutIntegrationTest.java)

| 订单状态 | 顾客列表与详情 | 商家、骑手 |
| --- | --- | --- |
| 0 待支付 / 1 正在支付 | 0 显示待付金额、支付与取消。1 显示订单金额和上述继续验证／查询入口。详情印章使用支付含义。 | 不在商家待接单中，不产生配送任务。 |
| 2 已支付、5 制作中、6 待骑手、7 配送中、8 已送达、3 已完成 | 沿用服务端 `statusDesc` 和实付金额。2 提示“等待商家接单”，8 才显示确认收货。 | 2 可接单或拒单，5 可出餐。骑手沿用配送任务自己的状态与接口。 |
| 4 已取消 | 显示已取消和订单金额，不推断退款。 | 沿用当前列表范围。 |
| 9 REFUNDING / 10 REFUNDED | 9 用警示色及“退款处理中，可稍后刷新”，10 用完成色及“已退款”。标题以 `statusDesc` 为准，印章和金额标题同步切换，金额标“订单金额”。两态都只读，无支付、取消、申请退款或确认收货按钮。 | 商家已打开的详情按同一状态展示，无接单、拒单、出餐按钮。骑手工作台不新增退款状态。 |
| 状态码未知或缺失 | 优先显示返回的 `statusDesc`，否则使用中性“订单状态待确认”，金额标“订单金额”，仅允许查看／刷新。 | 同样收起状态相关动作。 |

**商家能看到的范围。** `PENDING=[2]`、`PROCESSING=[5]`、`HISTORY=[6,7,8,3]`。三种服务端分页均排除 4、9、10。当前 HISTORY 提示却写着包含“已取消”。拒单确认改为“拒单后将发起退款”，成功提示“拒单请求已受理”，随后刷新列表。由列表发起时用当次已知 ID 打开现有抽屉，抽屉内发起则原位刷新。拒单响应丢失时同样按已知 ID 查，行消失本身不能证明退款成功。关掉抽屉或跨会话后，当前接口不提供退款订单检索。商家新单 SSE 和已读标记继续只服务待接单提醒。历史模拟已付单可能缺少成功 Stripe 记录，拒单会报业务错误，按返回原因提示并重读。[商家列表查询](../src/main/java/org/example/takeout/Merchant/Service/MerchantOrderViewService.java) · [列表筛选测试](../src/test/java/org/example/takeout/Order/Service/OrderMerchantQueryTest.java)

**展示边界。** 顾客列表/详情、商家列表/详情 VO 都未暴露退款 ID、时间、渠道到账时间、失败原因、人工复核状态和退款金额。商家详情的 `payTime` 是支付时间。只用订单状态描述进度，不把订单总额标为“已到账金额”，不承诺到账期限，也不把所有 9 都解释成商家拒单。骑手的配送任务状态 0/1/2 与订单枚举独立，退款单不应因前端展示产生抢单、出餐或配送动作。[订单枚举](../src/main/java/org/example/takeout/Order/Enums/OrderStatusEnum.java) · [顾客详情 VO](../src/main/java/org/example/takeout/Order/VO/OrderDetailVO.java) · [商家详情 VO](../src/main/java/org/example/takeout/Merchant/VO/MerchantOrderDetailVO.java)

## 四、可独立交付的阶段

下列路径相对 `merchant-web/`，仅列后续阶段预期改动。使用既有 Vue 页面、顾客 HTTP 客户端及 `node:test`。Element 的生命周期可由一个局部组件承载，不需要全局支付 store、通用状态机或新的后端接口。

### A · 锁定接口与测试替身

- **涉及文件**　`src/api/customerApi.js`、`test/api-fixture.mjs`，新增 `test/customer-payment-api.test.mjs`。
- **实现要求**　增加创建／复用、查本地、主动同步三个方法，编码路径 ID，透传当前会话校验与取消请求所需的配置。fixture 用沙盒风格的虚构数据区分无记录、已领取未绑定、可付款、处理中和已支付。同一订单复用同一虚构 Intent。旧支付入口在阶段 D 切换时移除调用。
- **测试范围**　沿用 `test/auth-http.test.mjs` 的 Axios adapter 检查方法、路径、无 body、角色、业务码和 503。断言 GET/sync 不返回凭证。重复 POST、POST 返回空凭证、串号访问、sync 无本地记录分别覆盖。
- **验收标准**　测试可离线独立运行。fixture 的 API 行为可以复现“请求结束但订单仍是 1”，测试替身只存在于 QA 环境。

### B · 先修正退款和三端状态展示（可与 A 并行）

- **涉及文件**　`src/display.js`、`src/customer/CustomerOrdersView.vue`、`src/customer/CustomerOrderView.vue`、`src/components/OrderListPanel.vue`、`src/components/OrderDetailDrawer.vue`、`test/api-fixture.mjs`，新增 `test/order-status-display.test.mjs`。与 A 合并 fixture 时分开修改支付路由与退款场景。
- **实现要求**　在既有展示辅助函数补 9/10 与中性兜底。修正详情的支付成功判断、印章、金额标题及两处拒单文案，纠正 HISTORY 说明。打开 9 的商家抽屉时提供原位刷新，顾客详情的刷新也只取订单。给 fixture 预置有成功支付记录的商家订单，让拒单从直接置 4 改为可观察的 2→9→10，同时覆盖停在 9 以及无成功支付记录时报错。HISTORY 仍按真实集合返回。
- **测试范围**　用 `node:test` 覆盖 0–10 与未知值。用 fixture 走顾客列表→详情、商家列表／抽屉拒单两条入口、拒单超时后查详情、10→9 更新及拒单失败。回归 2 接单、5 出餐、8 确认收货和骑手任务状态。
- **验收标准**　退款单从待接单消失、商家已知 ID 的详情及顾客订单可读。9/10 无履约或再次付款按钮，无“模拟退款”“配送中”或到账承诺。刷新后以最新详情为准。可单独交付这一阶段。

### C · 支付状态同步、刷新与回跳恢复（依赖 A）

- **涉及文件**　`src/customer/CustomerOrderView.vue`、`index.html`，按需新增小型入口脚本 `public/payment-return.js` 与 `test/payment-recovery.test.mjs`。沿用 `src/router.js` 现有详情路由和登录守卫。
- **实现要求**　按第二节落地订单读取、状态 1 的本地查询、有限同步、停止条件和干净回跳。对跨订单、跨 token 的异步响应做失效处理。清理下单记录时核对顾客和已记录订单 ID，只在读到已知的后续订单状态时清除对应记录。保留未完成、未知或属于另一笔订单的记录。
- **测试范围**　从预置的 1 开始，覆盖本地无记录、同步仍为 1、Webhook 先到、503、离线、轮询到限、页面隐藏和卸载、切订单与换号后的晚响应。浏览器用虚构回跳参数验证根路径、子目录、重复或损坏的订单 ID、未登录再登录及普通三端深链。
- **验收标准**　刷新和银行返回都能定位到同一笔有权访问的订单。普通浏览只发读取请求，网络面板中没有 `POST /order` 或 `POST /order/{id}/payment`，轮询到限即停。登录 redirect、地址栏及应用日志无 Stripe 回跳凭证，旧顾客的请求不污染新会话。

### D · 接入 Payment Element 并切换顾客付款（依赖 A、C）

- **涉及文件**　`package.json`、`package-lock.json`、`src/api/customerApi.js`、`src/customer/CheckoutView.vue`、`src/customer/CustomerOrderView.vue`，新增 `src/customer/OrderPaymentForm.vue`、`test/customer-payment-flow.test.mjs`。如需浏览器离线演练，仅在 `test/vite.qa.config.mjs` 内绑定 SDK 替身。
- **实现要求**　只在顾客准备付款时加载 Stripe.js。详情负责取订单、锁定提交按钮、创建／复用及后续核对。局部表单仅负责 Elements、确认或认证、销毁。替换结算及详情里的模拟支付文案，移除旧 `payOrder` 调用。下单成功仍先保存 `orderId` 并进入详情，支付重试不会进入 `POST /order`。
- **测试范围**　用受控 SDK 和 A 的接口覆盖正常提交、双击、两标签页同单、无密钥、SDK 加载失败、拒付后换卡、3DS 成功／取消、断网、确认后即关页和恢复时后台已退款。验证不带金额、不会误触 `/pay`、不会自动确认，只有真实状态允许才重新开放按钮。回归 `src/customer/checkoutIntent.test.mjs`、`src/customer/checkoutCoordinates.test.mjs` 和 `test/auth-http.test.mjs`。
- **验收标准**　一笔订单对应一个 Intent。得知后端已收款前页面不报成功。顾客换号后旧 Element 消失。商家、骑手页面不加载支付表单。关开页面后，0 可发起、1 可继续或核对、9/10 只能查看。该阶段先在沙盒验证。

### E · 沙盒验收与交付记录（依赖 B、D）

- **涉及文件**　`test/QA-FIXTURE.md`、`test/QA-RESULTS.md`、`FRONTEND.md`、`README.md`。保留旧模拟 QA 的日期与范围，补记新版 Vue 的实测结果、环境及未验证项。
- **实施要求**　在 `merchant-web/` 运行 `npm test` 和 `npm run build`，用已有 Spring Boot 沙盒环境、同环境公钥与已验签的 Webhook 做浏览器验收。静态 `stripe-test.html` 的历史成功记录和内存 fixture 各自只证明其覆盖的路径。
- **测试范围**　一笔成功支付走完整履约链 `0→1→2→5→6→7→8→3`。另一笔成功支付供商家拒单，核对 9/10、商家列表与已开抽屉。另验证拒付或认证退出后同单恢复、真实 3DS、重定向方法的返回（以账户已启用方式为准）、Webhook 延迟与补查、Stripe 关闭时不回退模拟。并发/人工复核/迟到失败等难以稳定制造的分支用 fixture 和现有后端测试取证，明确标注来源。
- **验收标准**　记录订单 ID、脱敏后的状态序列及实际结果。沙盒内核对同单 Intent 数量、退款结果与订单状态。商家只在收款提交后看到新单提醒，已读逻辑和骑手抢单、送达均能按原流程完成。真实回跳和认证中断续接未经验证的支付方式列为待验。

### 已有测试可证明什么

- [支付 Controller 测试](../src/test/java/org/example/takeout/Payment/StripePaymentControllerTest.java) 与 [鉴权测试](../src/test/java/org/example/takeout/Payment/StripePaymentAuthTest.java) 覆盖请求边界、禁缓存和顾客鉴权。[支付服务测试](../src/test/java/org/example/takeout/Payment/Service/StripePaymentServiceTest.java) 和 [支付事务测试](../src/test/java/org/example/takeout/Payment/Service/StripePaymentTransactionServiceTest.java) 覆盖同单复用、创建响应丢失、退避、取消竞争与事件去重。
- [退款链路测试](../src/test/java/org/example/takeout/Payment/Service/StripeRefundFlowTest.java) 覆盖重复拒单、库存只恢复一次、异步推进及失败留在 9。[配送状态测试](../src/test/java/org/example/takeout/Order/Service/OrderDeliveryStateQueryTest.java) 约束可配送与配送完成的状态集合。浏览器的支付确认、凭证清理和回跳需在 C–E 单独验证。
- 前端另有商家通知及骑手客户端、定位与距离回归，已由 `npm test` 收录。[现有前端测试脚本](../merchant-web/package.json) 若后续复跑 Java 基线，先核对 `pom.xml` 的 Java 17 编译目标与当前 JDK，按项目规则在需要时仅对当前进程切换已安装的 JBR 21。常规测试优先 `rtk mvn`。

## 五、待确认事项与交付顺序

| 待确认事项 | 由谁确认 | 对当前交付的影响 |
| --- | --- | --- |
| 关闭 Stripe 的部署是否还要保留显式可选的模拟支付 | 产品与部署负责人 | 本计划选择新版入口仅走 Stripe 沙盒。若要双模式，需另行约定可验证的模式配置及文案。不能靠失败后切换。 |
| 商家是否需要跨会话检索退款单，应归入 HISTORY 还是单独展示 | 业务与后端负责人 | B 可交付当前详情展示。完整检索需要另外确定服务端分页契约。 |
| 退款原因、分项金额、部分退款、到账提示及长期未定时的客服入口 | 业务与后端负责人 | 现有链路按成功支付全额发起。当前仅展示订单级状态和订单金额，联系渠道及承诺时效待确认。 |
| 沙盒启用哪些支付方式、哪些认证中断可续接，实际部署入口及日志配置 | 支付与部署负责人 | D/E 按实测方式验收。生产 live 模式超出现有后端配置，本计划不作为生产收款上线依据。 |

建议先做 **A 与 B**，二者可各自验收。顾客支付链按 **C → D** 执行，把“查回同一订单”的能力放在开放客户端确认之前。**E** 再把顾客、商家与骑手连起来验证。商家退款历史检索和生产模式可以单独决策，不阻塞沙盒首笔支付。
