package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.Service.CartCommandService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Config.OrderTimeoutTask;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.Service.OrderTransactionExecutor;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Product.Config.ProductImageCleanupService;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.dataFactory.TestDataFactory;
import org.example.takeout.testsupport.ConcurrentTestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * First-phase destructive order tests. These run only against the explicitly
 * provisioned MySQL and Redis ports used by the reliability lab.
 */
@EnabledIfSystemProperty(named = "takeout.lab.enabled", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=jdbc:mysql://localhost:${takeout.test.mysql.port}/takeout_integration_test?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=${takeout.test.redis.port}",
        "spring.data.redis.database=0",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=false",
        "payment.stripe.enabled=false",
        "order.timeout.enabled=true",
        "order.timeout.initial-delay-ms=3600000",
        "order.timeout.scan-interval-ms=3600000",
        "delivery.geo.reconciliation.enabled=false",
        "jwt.secret=test-secret-key-at-least-32-characters-long!!",
        "jwt.expire-days=7"
})
class PhaseOneOrderConcurrencyIntegrationTest {

    private static final AtomicLong ID_BASE_SEQUENCE = new AtomicLong(20_000_000L);
    private static final int CONCURRENT_BUYERS = 12;
    private static final int INITIAL_HOT_STOCK = 4;

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private OrderTimeoutTask orderTimeoutTask;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OrderTransactionExecutor orderTransactionExecutor;

    @MockitoSpyBean
    private CartCommandService cartCommandService;

    @MockitoBean
    private ProductImageCleanupService productImageCleanupService;

    private long idBase;
    private long merchantId;
    private long categoryId;
    private long productId;
    private Product product;

    @BeforeEach
    void setUp() {
        assertNotNull(System.getProperty("takeout.test.mysql.port"),
                "测试必须显式指向临时 MySQL 端口");
        assertNotNull(System.getProperty("takeout.test.redis.port"),
                "测试必须显式指向临时 Redis 端口");
        idBase = ID_BASE_SEQUENCE.getAndAdd(10_000L);
        merchantId = idBase + 1;
        categoryId = idBase + 2;
        productId = idBase + 3;
        deleteTestData();
    }

    @AfterEach
    void tearDown() {
        try {
            deleteTestData();
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void multipleUsersCompetingForHotProductCannotOversell() {
        seedMerchantAndProduct(INITIAL_HOT_STOCK);
        List<Long> userIds = new ArrayList<>();
        List<CreateOrderDTO> requests = new ArrayList<>();
        for (int buyerIndex = 0; buyerIndex < CONCURRENT_BUYERS; buyerIndex++) {
            long userId = idBase + 100 + buyerIndex;
            userIds.add(userId);
            addCartItem(userId, 1, buyerIndex);
            requests.add(TestDataFactory.createOrderDTO());
        }

        ConcurrentLinkedQueue<Long> createdUserIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<BusinessException> rejectedRequests = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

        ConcurrentTestTemplate.runConcurrently(
                CONCURRENT_BUYERS,
                Duration.ofSeconds(45),
                workerIndex -> {
                    Long userId = userIds.get(workerIndex);
                    try {
                        createOrderForUser(userId, requests.get(workerIndex));
                        createdUserIds.add(userId);
                    } catch (BusinessException expectedRejection) {
                        rejectedRequests.add(expectedRejection);
                    } catch (Throwable failure) {
                        unexpectedFailures.add(failure);
                    }
                });

        assertTrue(unexpectedFailures.isEmpty(), unexpectedFailures.toString());
        assertEquals(INITIAL_HOT_STOCK, createdUserIds.size());
        assertEquals(CONCURRENT_BUYERS - INITIAL_HOT_STOCK, rejectedRequests.size());
        assertTrue(rejectedRequests.stream().allMatch(this::isExpectedStockContentionRejection),
                "拒绝必须来自购物车不可用或事务内库存扣减失败，实际为 " + rejectedRequests);

        Product exhaustedProduct = productMapper.selectById(productId);
        assertEquals(0, exhaustedProduct.getStock());
        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), exhaustedProduct.getStatus());
        assertEquals((long) INITIAL_HOT_STOCK, orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().in(Order::getUserId, userIds)).longValue());
        assertEquals((long) INITIAL_HOT_STOCK, orderItemMapper.selectCount(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getProductId, productId)).longValue());
        assertEquals((long) INITIAL_HOT_STOCK, jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM order_item WHERE product_id = ?",
                Long.class,
                productId).longValue());

        for (Long userId : userIds) {
            boolean orderCreated = createdUserIds.contains(userId);
            assertEquals(!orderCreated, cartMapper.existsByUserId(userId),
                    "成功下单的购物车应被消费，库存拒绝的购物车应保留");
            if (orderCreated) {
                assertNull(CartHeaderTestFixture.merchantId(jdbcTemplate, userId));
            } else {
                assertEquals(merchantId, CartHeaderTestFixture.merchantId(jdbcTemplate, userId));
            }
        }
    }

    @Test
    void timeoutScannerCannotSeeUncommittedOrderAndRestoresStockOnlyOnceAfterExpiry() {
        seedMerchantAndProduct(4);
        long userId = idBase + 100;
        addCartItem(userId, 1, 0);
        CreateOrderDTO request = TestDataFactory.createOrderDTO();
        String requestId = request.getRequestId();
        ConcurrentTestTemplate.Checkpoint orderInsertComplete = ConcurrentTestTemplate.checkpoint(
                "订单行已插入但创建事务尚未提交", Duration.ofSeconds(20));
        ConcurrentTestTemplate.Checkpoint timeoutScanComplete = ConcurrentTestTemplate.checkpoint(
                "超时扫描已完成", Duration.ofSeconds(20));

        CartCommandService targetSpy =
                (CartCommandService) AopTestUtils.getUltimateTargetObject(cartCommandService);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                    "购物车消费应处于订单创建事务内");
            Order insertedOrder = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getRequestId, requestId));
            assertNotNull(insertedOrder,
                    "同一事务必须能读到已插入但尚未提交的订单");
            orderInsertComplete.signal();
            timeoutScanComplete.awaitSignal();
            return invocation.callRealMethod();
        }).when(targetSpy).consumeCheckedOutItems(eq(userId), any());

        ConcurrentTestTemplate.TwoTaskResult<CreateOrderVO, Boolean> result =
                ConcurrentTestTemplate.runTwoTasks(
                        Duration.ofSeconds(25),
                        () -> createOrderForUser(userId, request),
                        () -> {
                            orderInsertComplete.awaitSignal();
                            try {
                                orderTimeoutTask.cancelTimeoutOrders();
                                Integer visibleOrders = jdbcTemplate.queryForObject(
                                        "SELECT COUNT(*) FROM orders WHERE user_id = ? AND request_id = ?",
                                        Integer.class,
                                        userId,
                                        requestId);
                                assertEquals(0, visibleOrders,
                                        "未提交的订单不应被独立扫描事务读取");
                                return true;
                            } finally {
                                timeoutScanComplete.signal();
                            }
                        });

        assertNotNull(result.firstResult());
        assertTrue(result.secondResult());
        Long orderId = result.firstResult().getOrderId();
        Order newlyCommittedOrder = orderMapper.selectById(orderId);
        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), newlyCommittedOrder.getStatus());
        assertEquals(3, productMapper.selectById(productId).getStock());

        orderTimeoutTask.cancelTimeoutOrders();
        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), orderMapper.selectById(orderId).getStatus(),
                "刚提交的新订单的 30 分钟到期时间尚未到达");
        assertEquals(3, productMapper.selectById(productId).getStock());

        LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(31);
        jdbcTemplate.update("""
                UPDATE orders
                SET create_time = ?, timeout_cancel_available_at = ?
                WHERE id = ? AND user_id = ? AND request_id = ?
                """, expiredAt, expiredAt, orderId, userId, requestId);

        orderTimeoutTask.cancelTimeoutOrders();
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(orderId).getStatus());
        assertEquals(4, productMapper.selectById(productId).getStock());

        orderTimeoutTask.cancelTimeoutOrders();
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(orderId).getStatus());
        assertEquals(4, productMapper.selectById(productId).getStock(),
                "重复扫描不能再次归还同一订单库存");
    }

    @Test
    void cancelledOrderCanRestoreStockWhileAnotherUserCompletesPurchase() {
        seedMerchantAndProduct(2);
        long cancellingUserId = idBase + 100;
        long purchasingUserId = idBase + 101;
        addCartItem(cancellingUserId, 1, 0);
        CreateOrderDTO firstRequest = TestDataFactory.createOrderDTO();
        var cancelledOrderView = createOrderForUser(cancellingUserId, firstRequest);
        assertEquals(1, productMapper.selectById(productId).getStock());

        addCartItem(purchasingUserId, 1, 1);
        CreateOrderDTO secondRequest = TestDataFactory.createOrderDTO();
        ConcurrentTestTemplate.Checkpoint purchaseSnapshotReady = ConcurrentTestTemplate.checkpoint(
                "购买方已读取可用购物车快照", Duration.ofSeconds(20));
        ConcurrentTestTemplate.Checkpoint cancellationCommitted = ConcurrentTestTemplate.checkpoint(
                "取消订单及库存回补已提交", Duration.ofSeconds(20));

        doAnswer(invocation -> {
            CreateOrderDTO actualRequest = invocation.getArgument(1);
            if (secondRequest.getRequestId().equals(actualRequest.getRequestId())) {
                purchaseSnapshotReady.signal();
                cancellationCommitted.awaitSignal();
            }
            return invocation.callRealMethod();
        }).when(orderTransactionExecutor).executeOrderCreation(
                any(), eq(secondRequest), eq(purchasingUserId), anyString());

        ConcurrentTestTemplate.TwoTaskResult<CreateOrderVO, Boolean> result =
                ConcurrentTestTemplate.runTwoTasks(
                        Duration.ofSeconds(25),
                        () -> createOrderForUser(purchasingUserId, secondRequest),
                        () -> {
                            purchaseSnapshotReady.awaitSignal();
                            try {
                                UserContextHolder.setUserId(cancellingUserId);
                                orderService.cancelOrder(cancelledOrderView.getOrderId());
                                return true;
                            } finally {
                                UserContextHolder.clear();
                                cancellationCommitted.signal();
                            }
                        });

        assertNotNull(result.firstResult());
        assertTrue(result.secondResult());
        Order cancelledOrder = orderMapper.selectById(cancelledOrderView.getOrderId());
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), cancelledOrder.getStatus());
        Order purchasingOrder = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, purchasingUserId)
                .eq(Order::getRequestId, secondRequest.getRequestId()));
        assertNotNull(purchasingOrder);
        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), purchasingOrder.getStatus());
        assertEquals(1, productMapper.selectById(productId).getStock());
        assertEquals(ProductStatusEnum.ON_SALE.getCode(), productMapper.selectById(productId).getStatus());
        assertFalse(cartMapper.existsByUserId(purchasingUserId));
        assertNull(CartHeaderTestFixture.merchantId(jdbcTemplate, purchasingUserId));
        assertEquals(2L, orderItemMapper.selectCount(Wrappers.<OrderItem>lambdaQuery()
                .in(OrderItem::getOrderId, cancelledOrderView.getOrderId(), purchasingOrder.getId())).longValue());
    }

    private void seedMerchantAndProduct(int stock) {
        Merchant merchant = TestDataFactory.createOpenMerchant(merchantId);
        merchantMapper.insert(merchant);
        jdbcTemplate.update("""
                INSERT INTO category (id, merchant_id, category_name, status, is_default)
                VALUES (?, ?, ?, 0, 0)
                """, categoryId, merchantId, "phase_one_category_" + categoryId);
        product = TestDataFactory.createProduct(productId,
                "阶段一热点商品 " + productId, stock, merchantId);
        product.setCategoryId(categoryId);
        productMapper.insert(product);
    }

    private CartItem addCartItem(long userId, int quantity, int buyerIndex) {
        CartHeaderTestFixture.insertEmpty(jdbcTemplate, userId);
        CartHeaderTestFixture.bind(jdbcTemplate, userId, merchantId);
        CartItem item = TestDataFactory.createCartItem(
                idBase + 1_000 + buyerIndex,
                userId,
                product,
                quantity);
        cartMapper.insert(item);
        return item;
    }

    private CreateOrderVO createOrderForUser(long userId, CreateOrderDTO request) {
        UserContextHolder.setUserId(userId);
        try {
            return orderService.createOrder(request);
        } finally {
            UserContextHolder.clear();
        }
    }

    private boolean isExpectedStockContentionRejection(BusinessException failure) {
        if (failure.getCodeEnum() == ResultCodeEnum.CART_EMPTY) {
            return "购物车包含不可用商品或不可下单商家，请刷新购物车后重试"
                    .equals(failure.getMessage());
        }
        return failure.getCodeEnum() == ResultCodeEnum.BUSINESS_ERROR
                && "创建订单失败".equals(failure.getMessage());
    }

    private void deleteTestData() {
        if (idBase == 0) {
            return;
        }
        List<Long> orderIds = jdbcTemplate.queryForList(
                "SELECT id FROM orders WHERE user_id BETWEEN ? AND ?",
                Long.class,
                idBase + 100,
                idBase + 100 + CONCURRENT_BUYERS - 1);
        if (!orderIds.isEmpty()) {
            orderItemMapper.delete(Wrappers.<OrderItem>lambdaQuery().in(OrderItem::getOrderId, orderIds));
        }
        jdbcTemplate.update("DELETE FROM orders WHERE user_id BETWEEN ? AND ?",
                idBase + 100, idBase + 100 + CONCURRENT_BUYERS - 1);
        jdbcTemplate.update("DELETE FROM cart WHERE user_id BETWEEN ? AND ?",
                idBase + 100, idBase + 100 + CONCURRENT_BUYERS - 1);
        jdbcTemplate.update("DELETE FROM cart_header WHERE user_id BETWEEN ? AND ?",
                idBase + 100, idBase + 100 + CONCURRENT_BUYERS - 1);
        jdbcTemplate.update("DELETE FROM cache_invalidation_task WHERE cache_key = ?",
                "product:detail:" + (idBase + 3));
        jdbcTemplate.update("DELETE FROM product WHERE id = ?", idBase + 3);
        jdbcTemplate.update("DELETE FROM category WHERE id = ?", idBase + 2);
        jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", idBase + 1);
    }
}
