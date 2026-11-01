# Stripe 测试支付联调

这个单页只用于 Spring Boot 后端实验：在浏览器中输入用户登录令牌和订单 ID，通过 Stripe Payment Element 完成 CNY PaymentIntent 测试。它使用现有订单，不自行提交金额，也不保存登录令牌或支付 `client_secret`。现有 Vue 客户端不属于本轮改动。

当前使用 IDEA 启动 Spring Boot。2026-10-30 已完成一笔实际的 takeout 沙盒付款，并通过 Stripe CLI 回调和 MySQL 查询确认订单进入 PAID。浏览器付款、CLI 授权与 IDEA 密钥配置由用户在本机操作；没有读取或记录密钥值。离线测试结果和本次实际联调结果分别记录如下。

## 本次实际联调验收（2026-10-30）

- Stripe CLI 版本由用户确认为 `1.55.3`，已登录并选择 takeout 沙盒，使用菜单启动测试 Webhook 转发。
- 本地接口实测：测试页 HTTP 200；不带签名的 Webhook HTTP 400；未登录订单支付请求返回业务码 401、数据为空（HTTP 200 是项目既有错误返回格式）。
- 订单 `6` 属于 `demo_user`（用户 ID `1`），商品金额 `25.80` 元、配送费 `5.00` 元，总额 `30.80 CNY`，Stripe 金额为 `3080` 分。
- 用户通过 Payment Element 完成沙盒付款，CLI 在北京时间 `2026-10-30 09:30:16` 收到 `payment_intent.succeeded`，转发到 `/webhooks/stripe` 返回 HTTP 200。
- 独立数据库连接实际核验：订单状态 `2`（PAID）、`pay_time=2026-10-30 09:30:16`；支付记录为 `currency=cny`、`amount_minor=3080`、`stripe_status=succeeded`，与订单金额精确匹配。
- PaymentIntent 为 `pi_3UW4cQIVxytxbUOj0zKiChUI`；落库事件为 `evt_3UW4cQIVxytxbUOj03BBnN95`，与用户提供的 CLI 成功回调一致，成功事件记录数量为 `1`。这些 ID 是业务关联标识，不是密钥。
- MySQL 的 Flyway 历史记录确认 `V20261030_1__stripe_test_payments.sql`（版本 `20261030.1`）执行成功。

订单由 [本地测试数据脚本](../deploy/stripe-demo-user-order.sql) 准备，创建时状态为 WAIT_PAY、支付时间为空；同一事务写入一条商品明细并将商品 `1` 的库存从 `100` 扣至 `99`，明细库存版本为 `2`。付款后核验库存仍为 `99`，没有重复扣减。由于顾客前端尚无收货经纬度输入，本次没有通过完整下单接口，也没有验证配送地址和距离计费；订单中的地址为测试占位信息，配送费使用 `5.00` 元测试值。

本次确认了 PaymentIntent 创建、该沙盒的这笔 CNY 卡付款、Webhook 签名验证及成功事务落库。主动查询按钮、真实重复事件投递、拒付、3DS、数据库失败后的真实重投，以及 30 分钟临界竞争均未在本次人工联调中操作；相应的离线测试不能替代这些实际场景。没有实现或验证 Stripe 退款、自动对账或补偿。

## 环境变量

在**测试沙盒**的 API keys 页面创建仅供本机使用的 restricted test key，并按实际调用授予最小权限。创建及查询 PaymentIntent 需要 **Payment Intents: Write**（该权限包含读取）；Webhook 签名密钥是单独的 `whsec_…`，从 CLI listener 或 Dashboard Webhook 端点取得。前端只使用测试 publishable key `pk_test_…`。

在 IDEA 中打开 **Run → Edit Configurations → TokeoutApplication → Environment variables**，设置下列四项；保留既有数据库、Redis 和 JWT 配置。密钥只保存在本机的非共享运行配置中，不启用将配置存为项目文件。`application.yaml` 已经引用这些变量，无需填写实际值：

| 环境变量 | 用途 |
| --- | --- |
| `STRIPE_ENABLED` | 本地显式启用测试支付 |
| `STRIPE_SECRET_KEY` | restricted test key，必须以 `rk_test_` 开头 |
| `STRIPE_PUBLISHABLE_KEY` | 测试 publishable key，必须以 `pk_test_` 开头 |
| `STRIPE_WEBHOOK_SECRET` | Webhook 签名密钥 |

密钥不要写进源码、仓库内已跟踪配置、日志、截图或测试快照，也不要粘贴到聊天中。测试页只接收用户 JWT，不接收任何 Stripe API key 或签名密钥。项目根目录忽略 `.env` 文件；Spring Boot 不会因为该文件存在就自动加载它。

下面的 PowerShell 写法仅适用于直接从该终端启动后端，不会把变量注入另一个已经打开的 IDEA：

```powershell
$env:STRIPE_ENABLED = 'true'
$secret = Read-Host 'Stripe restricted test key (rk_test_)' -AsSecureString
$env:STRIPE_SECRET_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
$env:STRIPE_PUBLISHABLE_KEY = Read-Host 'Stripe test publishable key (pk_test_)'
$webhook = Read-Host 'Stripe webhook signing secret (whsec_)' -AsSecureString
$env:STRIPE_WEBHOOK_SECRET = [System.Net.NetworkCredential]::new('', $webhook).Password
```

然后在这个终端启动应用。测试密钥缺失或模式不符时，不要启动支付联调。

已确认连接的是 `takeout 沙盒`（`livemode=false`）。本次已实际创建 PaymentIntent 并完成 CNY 测试卡交易，证明 IDEA 使用的受限测试密钥足以完成本次创建请求。主动查询链路本次没有操作，因此其实际调用仍待单独验证；Write 包含 Read 是权限规则，不代表本次已经实测查询。如果收到权限错误，到 Workbench 请求日志核对 Payment Intents 权限，不能通过换用 live key 解决。

## 本地联调

下列步骤已用于本次订单验收，保留供后续重现。每次需要在 Dashboard、IDEA、CLI 或浏览器操作时，均由用户本机完成；不要把密钥、JWT 或含密钥的完整终端输出发给聊天。

1. 在本机 PowerShell 手动安装 [Stripe CLI](https://docs.stripe.com/cli/install)：

   ```powershell
   winget install Stripe.StripeCLI
   ```

   安装后重新打开终端，或双击仓库中的 [Stripe 本地联调菜单](../scripts/stripe-local.cmd)，选 **1** 检查 CLI 版本。菜单已经保存后续命令，无需记忆。
2. 菜单选 **2**，使用 `stripe login` 打开的浏览器完成授权，**只授权 takeout 沙盒**。再选 **3**，通过 `stripe switch` 确认选中 takeout 沙盒。不要选择真实环境，也不使用要求手动填写 API key 的 `login --interactive`。如果登录提示需要管理员启用 CLI access，在该沙盒的 Settings → Team and security → MCP and CLI access 中手动处理；遇到这一步先停下来确认。
3. 菜单选 **4**，保持窗口打开。等效命令是：

   ```powershell
   stripe listen --live=false --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled --forward-to http://127.0.0.1:8080/webhooks/stripe
   ```

   CLI 会在你的本地窗口显示当前 listener 的 `whsec_`。只把它复制到 IDEA 的 `STRIPE_WEBHOOK_SECRET`，不要发送到聊天，也不要把终端输出重定向到日志。CLI 可以先于后端启动，以便先取得签名密钥。
4. 在 IDEA 配齐三个 Stripe 变量及 `STRIPE_ENABLED=true`，启动既有 MySQL/Redis，再重新运行 Spring Boot。CLI 授权和 IDEA 中的 `rk_test_` / `pk_test_` 必须来自同一个 takeout 沙盒。等待应用及 Flyway 启动完成，先确认没有缺表或配置错误。
5. 准备本项目中同一用户的 JWT 和一笔**新建且未超过 30 分钟的待支付订单**。已有顾客页面可通过 `/#/customer/login` 登录、下单并从订单详情取得订单 ID；用户 JWT 在本机浏览器开发者工具 Network 的登录响应中复制，或从 `sessionStorage` 的 `takeout.customer.web.session` 中读取。也可以用既有接口调试工具完成登录与下单。JWT 只粘贴到本机测试页，不发给聊天。

   当前顾客前端缺少经纬度输入时，完整下单会被参数校验阻止。本次使用的 `stripe-demo-user-order.sql` 是针对现有 demo_user、商家和商品的一次性数据准备脚本：固定请求 ID 会阻止重复创建，不会重置已支付订单或延长支付期限。它必须使用不带 `--force` 的 MySQL 批处理执行，任何断言失败都应终止连接并回滚；不要直接重复执行来申请新订单。

6. 访问 `http://localhost:8080/stripe-test.html`，输入用户 JWT 和订单 ID，点一次“读取订单并准备支付”。确认显示 CNY 金额与订单人民币总额相同，再输入成功测试卡 `4242 4242 4242 4242`、未来有效期（例如 `12/34`）和任意三位测试 CVC，点击“确认测试付款”。不要输入真实银行卡信息。
7. 付款完成后只点击 **“读取本地状态”**；这调用 GET，不会主动查询 Stripe 或推进订单。观察 CLI 出现该 PaymentIntent 的 `payment_intent.succeeded` 事件及转发 HTTP 200。若仍待支付，先检查 listener、签名密钥和后端错误，保留现场；首次 Webhook 验收前不要点“Stripe主动查询（备用）”，也不要再次点创建支付。页面的备用按钮会调用可信的后端主动查询，可在另一次实验中验证，不能用它掩盖未接通的 Webhook。
8. 在 IDEA 的 MySQL 数据库控制台打开 [支付验收 SQL](../deploy/stripe-payment-verification.sql)，把开头的 `@stripe_order_id` 改为本次数字订单 ID 后执行。该脚本只修改会话变量并查询，不更新业务数据。先不要让商家接单，确认 `order_status=2`（PAID）、`pay_time` 非空、`stripe_status=succeeded`、`currency=cny`、`amount_matches=1`、`succeeded_webhook_events>=1`。保存这些不含密钥的结果，才可记录这笔真实沙盒链路验收通过。

拒付和 3DS 可以分别使用 `4000 0000 0000 0002`、`4000 0025 0000 3155` 在新测试订单中验证。CLI 的独立触发事件不关联你的本地订单，不能代替上述订单付款验收。

如果通过公网 HTTPS 测试，在该沙盒的 Workbench 创建事件目标，选择本账户及 **Snapshot** 事件格式，订阅上述三个 PaymentIntent 事件，地址设为 `https://你的测试地址/webhooks/stripe`。API 版本与 stripe-java 34 对齐（`2026-09-30.endive`）。CLI listener 和控制台端点的 `whsec_` 不同，使用当前通知来源对应的签名密钥。

新增接口：`POST /order/{id}/payment` 创建或复用支付请求，`GET /order/{id}/payment` 只读取本地记录，`POST /order/{id}/payment/sync` 主动查询 Stripe 并处理可信结果。它们都要求用户 JWT 并检查订单归属。`POST /webhooks/stripe` 仅依赖 Stripe 签名验证，不要求用户 JWT。只有创建接口会在订单仍可支付时返回 `clientSecret`，响应禁止缓存。

启用 Stripe 后，现有 `PATCH /order/{id}/pay` 模拟支付入口会拒绝直接支付；本轮使用独立测试页，不扩展 Vue 工程。支付过程仍由原订单状态机约束，`stripe_status` 只是保存 Stripe 原生状态的观察值，没有新增订单支付状态机。

数据库变更为 `V20261030_1__stripe_test_payments.sql`：新增 `order_stripe_payments` 及 `stripe_payment_events` 两张表，已同步到 `deploy/migrations` 和 `deploy/schema.sql`。现有库通过 Flyway 执行增量迁移；本次已只读核对运行库的成功迁移记录及真实支付数据。两张表仅存金额、币种、关联 ID、幂等键和事件 ID，不保存 API key、Webhook secret、`client_secret` 或完整支付报文。

## 金额与范围

币种固定为 CNY，不做汇率换算。服务端从数据库读取订单 `total_amount`，校验金额最多有两位小数，再精确乘以 100 转成 Stripe 的最小货币单位（例如 `¥12.34` 对应 `1234`）；客户端传来的价格不参与扣款金额计算。Stripe API 金额限制及账户可用性仍须由沙盒实际请求验证；请求失败时订单不会被当作支付成功。

本轮不做自动对账、取消补偿或退款。30 分钟超时和订单取消规则继续有效；取消与 Stripe 成功通知交错时，晚到事件可能造成 Stripe 支付成功而本地订单仍为取消状态。当前实现不会据此复活订单、恢复库存或通知商家；按要求先保留该状态差异，后续再手动实验研究。

商家拒单也保留原有模拟退款行为，本轮不会向 Stripe 发起退款。已下发给浏览器的 PaymentIntent 在订单取消后不会被远端自动撤销。临近 30 分钟期限才成功、但确认到达时已过期的付款，也可能只记录 Stripe 成功而不推进本地订单。Webhook 与主动查询共用短事务结算入口；同一事件或不同事件重复报告同一个成功 PaymentIntent，不会重复通知商家。

每笔订单仅持久化关联一个 PaymentIntent，网络失败和银行卡拒付重试复用这个对象。如果你在控制台把它取消，当前接口不会自动创建第二个 PaymentIntent。

参考：[受限密钥权限](https://docs.stripe.com/keys/restricted-api-keys)、[货币与最小金额单位](https://docs.stripe.com/currencies)、[Webhook 配置与签名](https://docs.stripe.com/webhooks)、[官方测试卡](https://docs.stripe.com/testing)。

## 离线验证（2026-10-30）

- Stripe 测试 42 项通过：金额精度、配置隔离、SDK 请求参数、合法及非法签名、环境字段/API 版本缺失、鉴权路径、失败重试、重复及乱序事件、并发创建、查询与回调竞争、取消/过期边界，以及网络调用期间暂停外层事务。
- 相关回归 66 项通过：`CartOrderApiTest` 45 项、`JwtInterceptorTest` 12 项、`OrderPaymentNotificationTransactionTest` 6 项、`OrderTimeoutTaskTest` 1 项、`OrderTimeoutCancellationTest` 2 项。合计 108 项，无失败或错误。
- 测试页两个内联脚本通过 Node.js 语法检查。上述 108 项不包含浏览器自动化付款；本次用户手动完成的实际沙盒付款及 MySQL 验收见本文开头。
- MySQL 专用迁移用例 `FlywayMigrationIntegrationTest#stripePaymentMigrationKeepsOrdersAndAddsDurableUniqueReferences` 已存在，本轮未运行该自动化用例。运行库的 Flyway 成功记录和真实订单数据已经另外查询确认，不把它计入离线测试数量。
- 上轮扩展运行 `OrderServiceIntegrationTest` 时，38 个用例因测试库缺少 `account_transaction` 表报错；本轮未重跑这一组，也没有把它计入通过数量。上述结果不是整个项目的全量测试通过结论。
- CLI 辅助脚本的 LF 换行曾导致 CMD 找不到 `listen` 标签，已修为 CRLF，并用替身命令实际复现修复前错误、验证修复后菜单分支。随后用户启动真实 listener 并提供本次成功回调；没有读取本地 CLI 凭据或 listener 的签名密钥。

普通离线回归命令（PowerShell 中将 `-D` 参数整体加引号）：

```powershell
mvn -o '-Dtest=Stripe*Test,OrderPaymentNotificationTransactionTest,OrderTimeoutTaskTest,OrderTimeoutCancellationTest,CartOrderApiTest,JwtInterceptorTest' test
```

本次环境中动态挂载测试代理曾在 Spring 上下文启动阶段等待，验证时显式指定了缓存中 Mockito 5.20.0 JAR 的 `-javaagent`，未修改项目的全局 Maven 测试配置。编译、相关改动的 `git diff --check` 及测试页语法检查均通过；没有执行 Git commit。

## Stripe 相关文件

- `pom.xml`：Stripe Java SDK；`src/main/resources/application.yaml`：环境变量占位配置。
- `src/main/java/org/example/takeout/Payment/Config/`、`Gateway/`：测试模式配置校验、实例化 SDK、PaymentIntent 创建和查询、Webhook 签名校验。
- `src/main/java/org/example/takeout/Payment/Controller/`、`Service/`、`Mapper/`、`Entity/`、`VO/`：鉴权接口、持久幂等关联、短事务应用支付结果、事件去重和安全响应。
- `src/main/java/org/example/takeout/Order/Service/OrderService.java`：复用原订单支付成功 CAS 与提交后通知；`Common/Auth/AuthPathMatcher.java`：Webhook 和测试静态页路径规则。
- `src/main/resources/db/migration/V20261030_1__stripe_test_payments.sql`、`deploy/migrations/` 对应副本和 `deploy/schema.sql`：支付关联与事件表。
- `src/main/resources/static/stripe-test.html`、`scripts/stripe-local.cmd`：最小支付操作页面和已保存命令的 CLI 菜单。
- `deploy/stripe-demo-user-order.sql`、`deploy/stripe-payment-verification.sql`：本次一次性测试订单数据准备和只读验收查询。
- `src/test/java/org/example/takeout/Payment/`、订单支付/超时相关测试及 `integration/FlywayMigrationIntegrationTest.java`：离线测试和迁移用例。
- `docs/stripe-test-payments.md`、`docs/README.md`：配置、运行、结果与范围记录。

该页面不是生产 checkout。Stripe Dashboard 的测试资料只用于模拟支付，不会移动真实资金；不要填入真实银行卡信息。
