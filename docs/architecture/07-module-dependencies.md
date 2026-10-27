# 跨模块依赖：按主题阅读

这些小图回答“某项职责依赖谁”。箭头统一表示 **调用方 → 被调用方**，不表示执行顺序或事务范围；先看 [分层总览](01-system-overview.md)，再选择需要的主题。

节点可归并承担同一职责的多个现有类，仍属于同一 Spring Boot 应用；没有新增统一服务或事件总线。Mapper、MySQL 与 Redis 关系只在总览中表达。

## 1. 店铺浏览与购物车的只读查询

店铺详情与购物车复用商品查询能力，各自组织返回数据。

```mermaid
flowchart TD
    Shop["店铺详情"] --> Category["分类查询"]
    Shop --> Product["商品查询"]
    Cart["购物车与结算准备"] --> Product
    Cart --> Merchant["商家查询"]
```

这里仅画跨模块查询依赖。店铺聚合直接查询商家；店铺商品、购物车与结算准备直接查库，不经过商品详情缓存。购物车节点包含写事务，图中的查询依赖不代表它整体只读。

源码定位：

| 图中职责 | 类与关键方法 |
| --- | --- |
| 店铺详情 | [CustomerShopQueryService](../../src/main/java/org/example/takeout/Merchant/Service/CustomerShopQueryService.java)：`getMerchantDetailWithGroupedProducts` 查询商品，`convertToCategoryVOList` 批量查询分类名 |
| 购物车与结算准备 | [CartService](../../src/main/java/org/example/takeout/Cart/Service/CartService.java)：`add/update/list`；[CartCheckoutService](../../src/main/java/org/example/takeout/Cart/Service/CartCheckoutService.java)：`prepareCheckout` |
| 查询能力 | [ProductQueryService](../../src/main/java/org/example/takeout/Product/Service/ProductQueryService.java)、[MerchantQueryService](../../src/main/java/org/example/takeout/Merchant/Service/MerchantQueryService.java)、[CategoryService.getCategoriesByIds](../../src/main/java/org/example/takeout/Category/Service/CategoryService.java) |

总表锁、版本校验和结算消费见 [05 购物车](05-cart.md)。

## 2. 订单、履约与配送

商家履约协调订单和配送；订单负责结算、库存及支付后的商家通知。

```mermaid
flowchart TD
    Fulfillment["商家履约"] --> Delivery["配送任务"]
    Fulfillment --> Order["订单服务"]
    Delivery --> Order
    Delivery --> Rider["骑手校验"]
    Order --> Cart["购物车结算与消费"]
    Order --> Product["库存命令"]
    Order --> Sse["商家 SSE"]
```

“订单服务”合并订单包内的编排、明细和状态命令；“商家履约”不包含商家 SSE，避免把不同职责误画成同一对 Bean 的循环依赖。商家订单展示另由 [MerchantOrderViewService](../../src/main/java/org/example/takeout/Merchant/Service/MerchantOrderViewService.java) 查询订单与明细、维护已读，未加入这张状态协作依赖图。

源码定位：

| 图中依赖 | 调用位置 |
| --- | --- |
| 履约 → 订单、配送 | [FulfillmentService.acceptOrder/rejectOrder/completePreparation](../../src/main/java/org/example/takeout/Merchant/Service/FulfillmentService.java) 调用 [OrderCommandService](../../src/main/java/org/example/takeout/Order/Service/OrderCommandService.java)、`DeliveryTaskService` |
| 配送 → 订单、骑手 | [DeliveryTaskService.claimTask/completeDelivery](../../src/main/java/org/example/takeout/DeliveryTask/Service/DeliveryTaskService.java) 调用订单状态命令/校验及 [RiderService.requireActiveRiderId](../../src/main/java/org/example/takeout/Rider/Service/RiderService.java) |
| 订单 → 购物车 | [OrderService.prepareOrderDataContext](../../src/main/java/org/example/takeout/Order/Service/OrderService.java) 调用结算准备；[OrderTransactionExecutor.executeOrderCreation](../../src/main/java/org/example/takeout/Order/Service/OrderTransactionExecutor.java) 调用消费命令 |
| 订单 → 库存、SSE | [OrderItemService](../../src/main/java/org/example/takeout/Order/Service/OrderItemService.java) 调用商品库存命令；`OrderService.payOrder` 提交后调用 [MerchantSseService.notifyNewOrder](../../src/main/java/org/example/takeout/Merchant/Service/MerchantSseService.java) |

事务不由图中的模块分组决定：创建准备在事务外，消费和扣库在创建事务内；出餐、抢单、送达各自保持订单与配送数据一致；SSE 在支付提交后执行。顺序与失败分支见 [02 订单创建](02-order-creation.md)、[03 订单状态与履约](03-order-lifecycle.md)。

## 3. 商品、分类与缓存补偿

商品管理校验分类，分类删除通过商品命令迁移商品；两条写入路径都能请求缓存失效。

```mermaid
flowchart TD
    Product["商品管理"] --> Category["分类管理"]
    Category --> Command["商品写命令"]
    Product --> Cache["商品详情缓存"]
    Command --> Cache
    Cache --> Task["缓存失效任务"]
    Scheduler["补偿调度"] --> Task
```

这是一条 `ProductService → CategoryService → ProductCommandService` 依赖链，三者是不同 Bean。缓存失效请求在业务事务内写任务，删除 Redis 在提交后执行；调度器调用任务服务重试，没有消息队列或任务领取锁。

源码定位：

| 图中依赖 | 类与关键方法 |
| --- | --- |
| 商品 → 分类 | [ProductService.createProduct/applyProductUpdate](../../src/main/java/org/example/takeout/Product/Service/ProductService.java) 调用 [CategoryService.lockActiveCategory](../../src/main/java/org/example/takeout/Category/Service/CategoryService.java)；展示路径也复用分类查询 |
| 分类 → 商品写命令 | `CategoryService.deleteById` 调用 [ProductCommandService.migrateProductsToCategory](../../src/main/java/org/example/takeout/Product/Service/ProductCommandService.java) |
| 商品管理/写命令 → 详情缓存 | [ProductDetailCacheService.getProductDetailCache/evictProductDetailCache](../../src/main/java/org/example/takeout/Product/Service/ProductDetailCacheService.java)；库存命令仅在有货/无货边界变化时请求失效 |
| 缓存/调度 → 失效任务 | [CacheInvalidationTaskService.requestInvalidation/retryPendingTasks/retryFailedTasks](../../src/main/java/org/example/takeout/CacheInvalidationTask/Service/CacheInvalidationTaskService.java)、[CacheInvalidationTaskScheduler](../../src/main/java/org/example/takeout/CacheInvalidationTask/Config/CacheInvalidationTaskScheduler.java) |

缓存正常读取、锁内重建、Redis 故障降级和提交后补偿分别见 [04 商品缓存](04-product-cache.md)。

## 4. 账号注册的初始化依赖

用户注册创建购物车总表，商家注册创建默认分类；两项初始化互不依赖。

```mermaid
flowchart TD
    User["用户注册"] --> CartHeader["购物车总表初始化"]
    Merchant["商家注册"] --> Category["默认分类初始化"]
```

两项初始化分别加入对应的注册事务，初始化失败会回滚账号插入。购物车总表在注册时创建，并非首次加购时懒创建。

源码定位：

| 图中依赖 | 调用位置与边界 |
| --- | --- |
| 用户 → 购物车总表 | [UserService.register](../../src/main/java/org/example/takeout/User/Service/UserService.java) 开事务，调用 [CartHeaderManager.insertCartHeader](../../src/main/java/org/example/takeout/CartHeader/Manager/CartHeaderManager.java)；被调用方法为 `MANDATORY` |
| 商家 → 默认分类 | [MerchantAccountService.register](../../src/main/java/org/example/takeout/Merchant/Service/MerchantAccountService.java) 开事务，调用 [CategoryCommandService.createDefaultCategory](../../src/main/java/org/example/takeout/Category/Service/CategoryCommandService.java) |

购物车后续绑定、修改和消费见 [05 购物车](05-cart.md)；入口与存储见 [01 分层总览](01-system-overview.md)。

## 5. 骑手位置与 Redis GEO

骑手位置由 Redis GEO 保存，并与最近活跃时间 ZSET 一起更新。位置服务直接依赖 `RiderService` 验证当前登录骑手，不依赖配送任务、订单或派单服务。

```mermaid
flowchart TD
    Location["骑手位置服务"] --> Rider["当前骑手身份及状态校验"]
    Location --> RedisGeo["Redis GEO 与最近活跃时间 ZSET"]
    Cleanup["位置清理调度"] --> Location
```

`updateLocation` 要求上报 ID 与当前有效骑手 ID 相同，经纬度合法；一个 Lua 脚本同时写入 `rider:locations` GEO 集合和 `rider:last_active` 活跃时间。附近查询按距离升序返回半径内最多 100 名骑手；单人位置读取和主动删除也由该服务提供。附近查询目前只查 GEO 集合，不检查最近活跃时间，因此位置是否仍新鲜依赖定时清理及时完成。

`RiderLocationCleanupScheduler` 使用 fixedDelay，每轮完成后等待 5 分钟。清理脚本删除最近活跃时间不晚于当前时间减 5 分钟的骑手，并同时从两个 Key 中移除；每轮至多运行 10 批、每批 100 个。超过当轮上限的积压留待后续调度轮次。Redis GEO 调用失败会向调用方抛 `RedisGeoOperationException`；调度器记录清理异常并等待下一轮。

这项能力目前尚未接入骑手 HTTP 控制器或配送派单流程，因此不能据此认为现有骑手 API 已能上报/搜索位置，也不参与任务分配。

| 位置 | 代码与测试 |
| --- | --- |
| 服务 | [RiderLocationService](../../src/main/java/org/example/takeout/Rider/Service/RiderLocationService.java)、[RiderDistanceVO](../../src/main/java/org/example/takeout/Rider/VO/RiderDistanceVO.java) |
| 过期清理 | [RiderLocationCleanupScheduler](../../src/main/java/org/example/takeout/Rider/Config/RiderLocationCleanupScheduler.java) |
| 行为验证源码 | [RiderLocationServiceTest](../../src/test/java/org/example/takeout/Rider/Service/RiderLocationServiceTest.java)、[RiderLocationServiceIntegrationTest](../../src/test/java/org/example/takeout/integration/RiderLocationServiceIntegrationTest.java)、[RiderLocationCleanupSchedulerTest](../../src/test/java/org/example/takeout/Rider/Config/RiderLocationCleanupSchedulerTest.java) |
