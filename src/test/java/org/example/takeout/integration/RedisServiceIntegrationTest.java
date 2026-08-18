package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Redis.RedisOrderCreationExperiment;
import org.example.takeout.Common.Redis.RedisOrderIdempotencyStore;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderTransactionExecutor;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.dataFactory.TestDataFactory;
import org.example.takeout.testsupport.ConcurrentTestTemplate;
import org.example.takeout.testsupport.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;

@SpringBootTest
@ActiveProfiles("redis-test")
public class RedisServiceIntegrationTest {
    private static final Long TEST_USER_ID = 9_001_001L;
    private static final Long TEST_OTHER_USER_ID = 9_001_002L;
    private static final Long TEST_MERCHANT_ID = 9_002_001L;
    private static final Long TEST_PRODUCT_ID_CUP = 9_003_001L;
    private static final Long TEST_PRODUCT_ID_MILK = 9_003_002L;
    private static final Long TEST_CART_ID_CUP = 9_004_001L;
    private static final Long TEST_CART_ID_MILK = 9_004_002L;
    private static final Long TEST_CATEGORY_ID = 9_005_001L;

    @Autowired
    private MerchantMapper merchantMapper;


    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private RedisOrderCreationExperiment redisOrderCreationExperiment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private RedisOrderIdempotencyStore redisOrderIdempotencyStore;

    @MockitoSpyBean
    private OrderTransactionExecutor orderTransactionExecutor;

    @BeforeEach
    void setUp() {
        RedisTestSupport.assumeRedisAvailable(redisTemplate);
        deleteTestData();
        UserContextHolder.setUserId(TEST_USER_ID);
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
    void shouldReturnSameOrderWhenRedisStateIsSucceeded() {
        insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 20);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5));
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15));

        CreateOrderDTO orderDTO = TestDataFactory.createOrderDTO();

        String requestId = orderDTO.getRequestId();
        Long userId = UserContextHolder.getUserId();

        CreateOrderVO first = redisOrderCreationExperiment.createOrder(orderDTO);

        String key = redisOrderIdempotencyStore.buildKey(userId,requestId);
        String redisValue = redisTemplate.opsForValue().get(key);

        assertEquals("SUCCEEDED:" + first.getOrderId(), redisValue);


        CreateOrderVO second = redisOrderCreationExperiment.createOrder(orderDTO);


        assertEquals(first.getOrderId(), second.getOrderId());

        assertEquals(1, orderMapper.selectCount(Wrappers.
                <Order>lambdaQuery().
                eq(Order::getUserId, userId).
                eq(Order::getRequestId, requestId)));

    }

    @Test
    void shouldRejectOrderCreationWhenRequestIsAlreadyProcessing() {
        CreateOrderDTO orderDTO = TestDataFactory.createOrderDTO();

        Long userId = UserContextHolder.getUserId();
        String requestId = orderDTO.getRequestId();

        insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 20);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5));
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15));



        boolean acquired = redisOrderIdempotencyStore.tryMarkProcessing(
                userId,
                requestId,
                Duration.ofSeconds(30)
        );

        assertTrue(acquired);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> redisOrderCreationExperiment.createOrder(orderDTO)
        );

        assertEquals("订单正在创建中", exception.getMessage());

        assertEquals(0, orderMapper.selectCount(Wrappers.
                <Order>lambdaQuery().
                eq(Order::getUserId, userId).
                eq(Order::getRequestId, requestId)));
    }

    @Test
    void shouldRejectConcurrentRequestBeforeTransactionWhileFirstRequestCompletes() {
        insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 20);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5));
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15));

        CreateOrderDTO orderDTO = TestDataFactory.createOrderDTO();
        String requestId = orderDTO.getRequestId();
        String redisKey = redisOrderIdempotencyStore.buildKey(TEST_USER_ID, requestId);
        ConcurrentTestTemplate.Checkpoint requestAHasProcessing =
                ConcurrentTestTemplate.checkpoint(
                        "请求 A 抢到 PROCESSING 并暂停",
                        Duration.ofSeconds(5)
                );
        ConcurrentTestTemplate.Checkpoint requestBHasReturned =
                ConcurrentTestTemplate.checkpoint(
                        "请求 B 在请求 A 暂停期间返回",
                        Duration.ofSeconds(5)
                );

        doAnswer(invocation -> {
            assertEquals(
                    "PROCESSING",
                    redisTemplate.opsForValue().get(redisKey),
                    "请求 A 进入事务前应已抢到 PROCESSING"
            );
            requestAHasProcessing.signal();
            requestBHasReturned.awaitSignal();
            return invocation.callRealMethod();
        }).when(orderTransactionExecutor).executeOrderCreation(
                any(OrderDataContext.class),
                same(orderDTO),
                eq(TEST_USER_ID)
        );

        try {
            ConcurrentTestTemplate.TwoTaskResult<CreateOrderVO, BusinessException> results =
                    ConcurrentTestTemplate.runTwoTasks(
                            Duration.ofSeconds(10),
                            () -> {
                                UserContextHolder.setUserId(TEST_USER_ID);
                                try {
                                    return redisOrderCreationExperiment.createOrder(orderDTO);
                                } finally {
                                    UserContextHolder.clear();
                                }
                            },
                            () -> {
                                UserContextHolder.setUserId(TEST_USER_ID);
                                try {
                                    requestAHasProcessing.awaitSignal();
                                    assertEquals(
                                            "PROCESSING",
                                            redisTemplate.opsForValue().get(redisKey),
                                            "请求 B 应观察到 PROCESSING"
                                    );

                                    return assertThrows(
                                            BusinessException.class,
                                            () -> redisOrderCreationExperiment.createOrder(orderDTO)
                                    );
                                } finally {
                                    requestBHasReturned.signal();
                                    UserContextHolder.clear();
                                }
                            }
                    );

            CreateOrderVO createdOrder = results.firstResult();
            BusinessException requestBFailure = results.secondResult();
            assertNotNull(createdOrder, "请求 A 应创建订单成功");
            assertNotNull(requestBFailure, "请求 B 应被 PROCESSING 状态拒绝");
            assertEquals("订单正在创建中", requestBFailure.getMessage());
            assertEquals(
                    "SUCCEEDED:" + createdOrder.getOrderId(),
                    redisTemplate.opsForValue().get(redisKey),
                    "请求 A 完成后应写入 SUCCEEDED"
            );
            assertEquals(1, orderMapper.selectCount(Wrappers.
                    <Order>lambdaQuery().
                    eq(Order::getUserId, TEST_USER_ID).
                    eq(Order::getRequestId, requestId)));

            verify(orderTransactionExecutor, times(1)).executeOrderCreation(
                    any(OrderDataContext.class),
                    same(orderDTO),
                    eq(TEST_USER_ID)
            );
        } finally {
            redisTemplate.delete(redisKey);
        }
    }

    @Test
    void shouldRejectConcurrentRequestBeforeTransactionWhileSecondRequestCompletes() {
        // 准备本来可以正常下单的数据
        Merchant merchant = insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 20);
        CartItem cupCartItem = TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5);
        CartItem milkCartItem = TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15);
        cartMapper.insert(cupCartItem);
        cartMapper.insert(milkCartItem);

        Long userId = UserContextHolder.getUserId();
        CreateOrderDTO orderDTO = TestDataFactory.createOrderDTO();
        String requestId = orderDTO.getRequestId();

        OrderDataContext orderDataContext = new OrderDataContext();
        orderDataContext.setAvailableItems(List.of(cupCartItem, milkCartItem));
        orderDataContext.setProductMap(Map.of(cup.getId(), cup, milk.getId(), milk));
        orderDataContext.setMerchant(merchant);
        orderDataContext.setTotalAmount(
                cup.getPrice().multiply(BigDecimal.valueOf(cupCartItem.getQuantity()))
                        .add(milk.getPrice().multiply(BigDecimal.valueOf(milkCartItem.getQuantity())))
        );


// 让事务执行器第一次调用时抛出异常
        doThrow(new RuntimeException("模拟数据库事务失败"))
                .doCallRealMethod()
                .when(orderTransactionExecutor)
                .executeOrderCreation(
                        argThat(context ->
                                context.getMerchant().getId().equals(orderDataContext.getMerchant().getId())
                                        && context.getAvailableItems().size()
                                        == orderDataContext.getAvailableItems().size()
                                        && context.getProductMap().keySet()
                                        .equals(orderDataContext.getProductMap().keySet())
                                        && context.getTotalAmount()
                                        .compareTo(orderDataContext.getTotalAmount()) == 0
                        ),
                        same(orderDTO),
                        eq(userId)
                );

// 第一次调用失败
        assertThrows(
                RuntimeException.class,
                () -> redisOrderCreationExperiment.createOrder(orderDTO)
        );

// PROCESSING 已被清理
        assertNull(redisOrderIdempotencyStore.get(userId, requestId));

// 数据库没有订单
        assertEquals(0, orderMapper.selectCount(Wrappers.<Order>lambdaQuery().
                eq(Order::getRequestId,requestId).
                eq(Order::getUserId,userId)));

// 第二次调用真实事务并成功
        CreateOrderVO retry =
                redisOrderCreationExperiment.createOrder(orderDTO);

        assertNotNull(retry.getOrderId());
        assertEquals(
                "SUCCEEDED:" + retry.getOrderId(),
                redisOrderIdempotencyStore.get(userId, requestId)
        );
    }

    @Test
    void shouldRecoverSucceededStateFromDatabaseIdempotencyAfterFirstRedisWriteFails() {
        insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 20);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5));
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15));

        CreateOrderDTO orderDTO = TestDataFactory.createOrderDTO();
        Long userId = UserContextHolder.getUserId();
        String requestId = orderDTO.getRequestId();
        RuntimeException redisWriteFailure = new RuntimeException("模拟 Redis 写入 SUCCEEDED 失败");

        doThrow(redisWriteFailure)
                .doCallRealMethod()
                .when(redisOrderIdempotencyStore)
                .markSucceeded(
                        eq(userId),
                        eq(requestId),
                        anyLong(),
                        eq(Duration.ofMinutes(10))
                );

        RuntimeException firstFailure = assertThrows(
                RuntimeException.class,
                () -> redisOrderCreationExperiment.createOrder(orderDTO)
        );
        assertSame(redisWriteFailure, firstFailure);

        assertNull(
                redisOrderIdempotencyStore.get(userId, requestId),
                "Redis 回写失败后应清理 PROCESSING，允许相同请求重试"
        );

        List<Order> persistedOrders = orderMapper.selectList(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getRequestId, requestId)
        );
        assertEquals(1, persistedOrders.size(), "Redis 回写失败不应回滚已提交的数据库订单");
        Order persistedOrder = persistedOrders.get(0);
        assertEquals(2, orderItemMapper.selectCount(
                Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderId, persistedOrder.getId())
        ));
        assertEquals(0, cartMapper.selectCount(
                Wrappers.<CartItem>lambdaQuery()
                        .eq(CartItem::getUserId, userId)
        ));

        CreateOrderVO retry = redisOrderCreationExperiment.createOrder(orderDTO);

        assertEquals(persistedOrder.getId(), retry.getOrderId());
        assertEquals(
                "SUCCEEDED:" + persistedOrder.getId(),
                redisOrderIdempotencyStore.get(userId, requestId),
                "重试应利用数据库中的幂等订单恢复 Redis SUCCEEDED"
        );
        assertEquals(1, orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getRequestId, requestId)
        ));
        verify(orderTransactionExecutor, times(1)).executeOrderCreation(
                any(OrderDataContext.class),
                same(orderDTO),
                eq(userId)
        );
        verify(redisOrderIdempotencyStore, times(2)).markSucceeded(
                eq(userId),
                eq(requestId),
                eq(persistedOrder.getId()),
                eq(Duration.ofMinutes(10))
        );
    }

    @Test
    void shouldFallBackToDatabaseIdempotencyWhenRedisIsUnavailableAtRequestEntry() {
        insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 20);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5));
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15));

        CreateOrderDTO orderDTO = TestDataFactory.createOrderDTO();
        Long userId = UserContextHolder.getUserId();
        String requestId = orderDTO.getRequestId();

        doThrow(new RedisConnectionFailureException("模拟 Redis 在请求入口不可用"))
                .when(redisOrderIdempotencyStore)
                .get(userId, requestId);

        CreateOrderVO first = redisOrderCreationExperiment.createOrder(orderDTO);
        CreateOrderVO second = redisOrderCreationExperiment.createOrder(orderDTO);

        assertNotNull(first.getOrderId());
        assertEquals(first.getOrderId(), second.getOrderId(), "相同 requestId 应返回同一数据库订单");
        assertEquals(1, orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getRequestId, requestId)
        ));
        assertEquals(2, orderItemMapper.selectCount(
                Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderId, first.getOrderId())
        ));
        assertEquals(0, cartMapper.selectCount(
                Wrappers.<CartItem>lambdaQuery()
                        .eq(CartItem::getUserId, userId)
        ));

        verify(redisOrderIdempotencyStore, times(2)).get(userId, requestId);
        verify(redisOrderIdempotencyStore, never()).tryMarkProcessing(
                anyLong(),
                anyString(),
                any(Duration.class)
        );
        verify(redisOrderIdempotencyStore, never()).markSucceeded(
                anyLong(),
                anyString(),
                anyLong(),
                any(Duration.class)
        );
        verify(orderTransactionExecutor, times(1)).executeOrderCreation(
                any(OrderDataContext.class),
                same(orderDTO),
                eq(userId)
        );
    }





    private Product insertProduct(Long id, String name, int stock) {
        Product product = TestDataFactory.createProduct(id, name, stock, TEST_MERCHANT_ID);
        product.setCategoryId(TEST_CATEGORY_ID);
        insertCategory();
        productMapper.insert(product);
        return product;
    }

    private Merchant insertMerchant() {
        Merchant openMerchant = TestDataFactory.createOpenMerchant(TEST_MERCHANT_ID);
        merchantMapper.insert(openMerchant);
        return openMerchant;
    }

    private void insertCategory() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO category (id, merchant_id, category_name, status, is_default)
                VALUES (?, ?, 'redis_service_test_category', 0, 0)
                """, TEST_CATEGORY_ID, TEST_MERCHANT_ID);
    }

    private void deleteTestData() {
        var orderIds = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                        .in(Order::getUserId, TEST_USER_ID, TEST_OTHER_USER_ID))
                .stream()
                .map(Order::getId)
                .toList();
        if (!orderIds.isEmpty()) {
            orderItemMapper.delete(Wrappers.<OrderItem>lambdaQuery()
                    .in(OrderItem::getOrderId, orderIds));
        }
        orderMapper.delete(Wrappers.<Order>lambdaQuery()
                .in(Order::getUserId, TEST_USER_ID, TEST_OTHER_USER_ID));
        cartMapper.delete(Wrappers.<CartItem>lambdaQuery()
                .in(CartItem::getUserId, TEST_USER_ID, TEST_OTHER_USER_ID));
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", TEST_MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM category WHERE merchant_id = ?", TEST_MERCHANT_ID);
        merchantMapper.deleteById(TEST_MERCHANT_ID);
    }
}
