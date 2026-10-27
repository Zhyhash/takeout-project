# 实现差异、并发边界与待确认事项

依据 2026-10-26 当前工作区源码及逐项处置结论。当前已实施 F01 的阈值测试修正、F04 的创建商品缓存失效，并恢复 F11 的历史 SUCCESS 清理；其他条目记录处置意见。

上述修正与恢复均指本次文档整理前已有的工作区实现；本次只静态复核并更新文档，没有修改业务代码或测试，也没有运行测试。

当前缓存补偿按**单实例、单消费者**运行。

| 编号 | 处理决定 |
| --- | --- |
| F01 | 已统一：第 5 次 worker 失败进入 FAILED，修正测试 |
| F02 | 接受当前缓存一致性窗口 |
| F03 | 当前无明显正确性问题；保留行数异常，排序是可选优化，本轮不改 |
| F04 | 已修复：新建商品也登记提交后缓存失效 |
| F05 | 接受事务外结算快照与现有重放边界 |
| F06 | 接受展示金额、可购买标记与实际结算的区别 |
| F07 | 当前忽略，不改结算判断 |
| F08 | 按数据不变量及异常数据边界记录，不补运行时懒创建 |
| F09 | 处置意见为仅改注释；本轮未实施 |
| F10 | 接受当前通知能力，多实例以后再评估，不上 MQ |
| F11 | 已恢复 SUCCESS 历史清理：创建满 7 天，每轮最多 1000 条 |

## F01 已统一为第 5 次失败转入低频重试

[`recordShortPeriodRetryFailure`](../../src/main/java/org/example/takeout/CacheInvalidationTask/Mapper/CacheInvalidationTaskMapper.java) 原实现已符合要求：新任务从计数 0 开始，第 1～4 次 worker 失败分别退避 10、30、60、120 秒，第 5 次转 FAILED、计数为 5，并保留上一次到期时间。正常路径不包含 300 秒档；提交后立即 DEL 的失败不增加该计数。

[`CacheInvalidationTaskServiceIntegrationTest`](../../src/test/java/org/example/takeout/CacheInvalidationTask/Service/CacheInvalidationTaskServiceIntegrationTest.java) 已将第 6 次失败的旧预期改为第 5 次，并验证各档退避与第 5 次保留原时间。FAILED 仍会由低频 worker 重试，不是终止状态。

## F02 接受现有缓存一致性窗口

[`ProductDetailCacheService`](../../src/main/java/org/example/takeout/Product/Service/ProductDetailCacheService.java) 使用有期限、无续期的重建锁；业务更新和失效 worker 不获取该锁。旧读取可能在删除后回填，延后重复删除只缩短常见窗口，不提供强一致保证。

**决定：接受当前窗口，不增加读写屏障或锁续期。** 初次补偿资格仍是创建任务时的应用时间加 15 秒，不是提交后固定等待 15 秒；慢事务或慢回源的边界保留在 [缓存文档](04-product-cache.md) 中。

## F03 单消费者下保留现有任务执行方式

**当前单实例、单消费者前提下没有明显正确性问题。** [`retryPendingTasks/retryFailedTasks`](../../src/main/java/org/example/takeout/CacheInvalidationTask/Service/CacheInvalidationTaskService.java) 保留 `rows != 1` 抛异常，以暴露状态异常，而不是无条件吞掉继续。

`ORDER BY` 属于可选的低成本优化，不是当前必须修复的问题，本轮不调整扫描逻辑。

没有任务领取机制、同一任务重复累计失败数、状态竞争导致更新零行等问题，**需要在将来引入多实例或多消费者时重新评估**；目前不为这些场景增加 PROCESSING、租约或领取锁。普通数据库异常仍可中断当前批次。

## F04 已增加新建商品的缓存失效

[`ProductService.createProduct`](../../src/main/java/org/example/takeout/Product/Service/ProductService.java) 在商品插入取得 ID 后调用现有 `evictProductDetailCache`，防止该 ID 先前的 `__NULL__` 空值缓存继续遮住新商品。

这不是事务内直接 DEL：商品和失效任务共同提交，`afterCommit` 才删 Redis；回滚不会提前删缓存。立即删除失败仍有 PENDING 任务补偿，不预热详情缓存。该修复沿用 F02 已接受的一致性语义。

[`ProductCreateCacheTransactionTest`](../../src/test/java/org/example/takeout/Product/Service/ProductCreateCacheTransactionTest.java) 使用独立 H2 与 Redis 测试替身，覆盖预存空值在提交后失效且详情可读、外层回滚、任务写入失败回滚，以及立即删除失败后的 worker 补偿。[`ProductServiceTest`](../../src/test/java/org/example/takeout/Product/Service/ProductServiceTest.java) 同时验证创建成功才请求失效、重名失败不失效及失效请求异常传播。

## F05 接受事务外准备的结算快照

[`OrderService.prepareOrderDataContext`](../../src/main/java/org/example/takeout/Order/Service/OrderService.java) 在创建事务外读取商品和商家并计价。写事务不重读价格与营业状态，但扣库存前会锁定商品行并读取当时的 `stock_version`，库存 SQL 还会重新要求商品未删除、在售且数量充足；订单明细记录的是扣减时的版本，不是准备阶段的快照版本。

**决定：接受准备阶段的价格/商家快照。** 不增加提交时价格确认或营业状态锁。指纹只包含收货信息和备注，复用同一请求号返回旧订单；并发准备失败仍只有 `CART_EMPTY` 补查旧单，其他准备异常直接失败。实际顺序见 [订单创建](02-order-creation.md)。

## F06 接受展示与结算的不同口径

[`CartService.list`](../../src/main/java/org/example/takeout/Cart/Service/CartService.java) 以购物车快照价格显示金额，以商品/商家状态判断可购买，不保证库存充分；加购不预占库存。下单按准备时查询的商品价格计价，最终由库存条件更新防止超卖。

**决定：接受此口径，不增加加购库存预占、库存额度校验或价格确认流程。** `canBuy=true` 不是库存扣减成功承诺，见 [购物车](05-cart.md)。

## F07 当前忽略结算状态判断的潜在扩展差异

加购、修改和列表要求商家状态等于营业；[`CartCheckoutService.isAvailable`](../../src/main/java/org/example/takeout/Cart/Service/CartCheckoutService.java) 要求商家存在且不等于打烊。当前正常 0/1 状态下结果一致。

**决定：当前不修。** 商品与总表的商家一致性依赖正常单店绑定，不把未知状态或绕过应用写入的脏数据当作已发生的正常链路漏洞；若将来扩展商家状态或增加导入入口，再复核相关约束。

## F08 购物车总表属于数据不变量

正常用户由 [`UserService.register`](../../src/main/java/org/example/takeout/User/Service/UserService.java) 在注册事务创建总表；历史用户由 [V20261010 迁移](../../src/main/resources/db/migration/V20261010__cart_header_backfill.sql) 补齐并校正绑定。因此正常路径应始终有且只有一行可锁定的用户总表。

**决定：作为不变量及异常数据边界记录，不增加懒创建或统一修复逻辑。** 绕过注册/迁移或人工破坏数据后，[`CartHeaderManager.lock`](../../src/main/java/org/example/takeout/CartHeader/Manager/CartHeaderManager.java) 可能返回空；加购、列表、准备和消费的缺行处理不同。这不等于当前正常业务存在缺总表问题。

## F09 失效商品处理注释与行为不一致

[`CartService.update`](../../src/main/java/org/example/takeout/Cart/Service/CartService.java) 的注释写着刷新列表会自动清理失效记录；实际列表不删除条目、不解绑、不刷新快照，失效商品由用户显式删除或清空。

**处置意见：改注释，不改业务。本轮未实施此项。**

## F10 接受当前单实例通知能力

[`MerchantSseService`](../../src/main/java/org/example/takeout/Merchant/Service/MerchantSseService.java) 使用当前进程的连接表，每商家一条连接；支付提交后发送刷新提示。没有连接直接返回，发送故障移除连接并记日志，不撤销支付。[`MerchantOrderViewService`](../../src/main/java/org/example/takeout/Merchant/Service/MerchantOrderViewService.java) 提供未读查询与显式已读能力。

**决定：接受现状，不引入 MQ。** 将来多实例时再评估跨实例通知；当前也不把 SSE 当作支付成功的唯一依据，或宣称后端接口保证前端已经进行补偿同步。

## F11 已恢复 SUCCESS 历史任务清理

[`CacheInvalidationTaskService.cleanupSuccessfulTasks`](../../src/main/java/org/example/takeout/CacheInvalidationTask/Service/CacheInvalidationTaskService.java) 删除 `created_time <= 当前应用时间 - 7 天` 的 SUCCESS 记录；[调度器](../../src/main/java/org/example/takeout/CacheInvalidationTask/Config/CacheInvalidationTaskScheduler.java) 每轮完成后等待 1 小时再次执行。

每轮按 `created_time, id` 升序最多删除 1000 条，不循环清空积压。PENDING 和 FAILED 无论多旧都保留。7 天按创建时间计算，不是成功时间；创建很早但最近才成功的记录也可能被下一轮清理。

[`CacheInvalidationTaskServiceIntegrationTest`](../../src/test/java/org/example/takeout/CacheInvalidationTask/Service/CacheInvalidationTaskServiceIntegrationTest.java) 覆盖状态隔离、包含 7 天边界、满批与分批清理顺序。
