package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=jdbc:mysql://localhost:3306/takeout_integration_test?createDatabaseIfNotExist=true&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.sql.init.mode=never",
        "jwt.secret=test-secret-key-at-least-32-characters-long!!",
        "jwt.expire-days=7"
})
class OrderServiceIntegrationTest {

    private static final Long TEST_USER_ID = 9_001_001L;
    private static final Long TEST_OTHER_USER_ID = 9_001_002L;
    private static final Long TEST_MERCHANT_ID = 9_002_001L;
    private static final Long TEST_PRODUCT_ID_CUP = 9_003_001L;
    private static final Long TEST_PRODUCT_ID_MILK = 9_003_002L;
    private static final Long TEST_CART_ID_CUP = 9_004_001L;
    private static final Long TEST_CART_ID_MILK = 9_004_002L;
    private static final Long TEST_CATEGORY_ID = 9_005_001L;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
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

    //NOTE：测试取消待支付订单时订单状态会变为已取消并恢复商品库存
    @Test
    void cancelOrder_shouldCancelOrderAndRestoreStock() {
        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Order order = insertOrder(OrderStatusEnum.WAIT_PAY.getCode());
        OrderItem item = TestDataFactory.createOrderItem(order.getId(), product, 1);
        orderItemMapper.insert(item);
        int stockBeforeCancellation = productMapper.selectById(product.getId()).getStock();

        orderService.cancelOrder(order.getId());

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(order.getId()).getStatus());
        assertEquals(
                stockBeforeCancellation + item.getQuantity(),
                productMapper.selectById(product.getId()).getStock()
        );
    }

    //NOTE：测试重复取消已取消订单时操作会失败且不会再次归还库存
    @Test
    void cancelOrder_shouldFailWhenOrderAlreadyCancelled() {
        insertMerchant();
        // 不插入 OrderItem / Product。
        // 如果错误地继续执行归还库存，这里应该会因为缺少关联数据而失败。
        // 测试通过说明状态校验后已直接返回，没有进入库存归还流程。
        Order order = insertOrder(OrderStatusEnum.CANCELLED.getCode());

        assertThrows(BusinessException.class, () -> orderService.cancelOrder(order.getId()));

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(order.getId()).getStatus());

    }

    //NOTE：测试并发取消同一订单时仅一次成功且库存只恢复一次
    @Test
    void cancelOrder_ConcurrentOrder() throws InterruptedException {
        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Order order = insertOrder(OrderStatusEnum.WAIT_PAY.getCode());
        OrderItem item = TestDataFactory.createOrderItem(order.getId(), product, 1);
        orderItemMapper.insert(item);
        int stockBeforeCancellation = productMapper.selectById(product.getId()).getStock();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Thread t1= new Thread(() -> {
            UserContextHolder.setUserId(TEST_USER_ID);
            try {
                latch.await();
                orderService.cancelOrder(order.getId());
                success.incrementAndGet();
            } catch (Throwable e) {
                firstFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });

        Thread t2= new Thread(() -> {
            UserContextHolder.setUserId(TEST_USER_ID);
            try {
                latch.await();
                orderService.cancelOrder(order.getId());
                success.incrementAndGet();
            } catch (Throwable e) {
                secondFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });

        t1.start();
        t2.start();

        latch.countDown();

        t1.join();
        t2.join();

        Order cancledOrder = orderMapper.selectById(order.getId());
        //最终一致性
        assertEquals(OrderStatusEnum.CANCELLED.getCode(), cancledOrder.getStatus());
        //库存一致
        assertEquals(stockBeforeCancellation + item.getQuantity(),
                productMapper.selectById(product.getId()).getStock());
        //并发应该一次成功一次失败
        assertEquals(1, success.get());
        assertEquals(1, fail.get());
        assertExpectedConcurrentCancelFailure(firstFailure.get(), secondFailure.get());
        //第n次取消应该会有异常（第二次也有，就是不知道怎么抓）
        assertThrows(BusinessException.class, () -> orderService.cancelOrder(cancledOrder.getId()));

    }

    //NOTE：测试库存不足时创建订单会失败且不修改库存、购物车和订单数据
    //NOTE：7.29补充，测试在事务失败后使用同一 requestId 重试是否成功
    @Test
    void createOrder_shouldFailWhenStockInsufficient() {
        insertMerchant();

        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "极简智能水杯", 10);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, product, 15));
        CreateOrderDTO orderDTO = TestDataFactory.createOrderDTO();
        assertThrows(
                BusinessException.class,
                () -> orderService.createOrder(orderDTO)
        );

        assertEquals(10, productMapper.selectById(product.getId()).getStock());
        assertEquals(1L, countTestUserCartItems());
        assertTrue(findTestUserOrder().isEmpty());


        product.setStock(100);
        productMapper.updateById(product);
        orderService.createOrder(orderDTO);
        assertEquals(85, productMapper.selectById(product.getId()).getStock());
        assertEquals(0L, countTestUserCartItems());
        assertFalse(findTestUserOrder().isEmpty());

    }

    //NOTE：测试在重复请求返回当前最新状态而不是历史快照
    @Test
    void createOrder_IdempotentQueryReturnsLatestSnapshot(){
        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        insertCartItem(product, 5);

        CreateOrderDTO dto = TestDataFactory.createOrderDTO();

        CreateOrderVO first = orderService.createOrder(dto);
        CreateOrderVO second = orderService.createOrder(dto);

        assertEquals(first.getOrderId(), second.getOrderId());

        List<Order> orders = findTestUserOrder();
        assertEquals(1, orders.size());
        assertEquals(
                OrderStatusEnum.WAIT_PAY.getCode(),
                orders.get(0).getStatus()
        );
        assertEquals(dto.getRequestId(), orders.get(0).getRequestId());
        assertEquals(5, productMapper.selectById(product.getId()).getStock());

        List<OrderItem> orderItems = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderId, first.getOrderId())
        );
        assertEquals(1, orderItems.size());
        assertEquals(5, orderItems.get(0).getQuantity());

        orderService.payOrder(orders.get(0).getId());
        List<Order> newOrders = findTestUserOrder();
        assertEquals(1, newOrders.size());
        assertEquals(OrderStatusEnum.PAID.getCode(), newOrders.get(0).getStatus());
    }

    //NOTE：测试购物车为空时创建订单会失败且不生成订单
    @Test
    void createOrder_shouldFailWhenCartEmpty() {
        insertMerchant();

        insertProduct(TEST_PRODUCT_ID_CUP, "极简智能水杯", 10);

        assertThrows(
                BusinessException.class,
                () -> orderService.createOrder(TestDataFactory.createOrderDTO())
        );

        assertTrue(findTestUserOrder().isEmpty());
    }

    //NOTE：测试第二件商品库存不足时创建订单相关操作会全部回滚
    @Test
    void createOrder_shouldRollbackAllWhenSecondProductStockInsufficient() {
        insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 10);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5));
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15));
        long orderItemCountBeforeCreation = orderItemMapper.selectCount(null);

        assertThrows(
                BusinessException.class,
                () -> orderService.createOrder(TestDataFactory.createOrderDTO())
        );

        assertEquals(orderItemCountBeforeCreation, orderItemMapper.selectCount(null));
        assertEquals(10, productMapper.selectById(cup.getId()).getStock());
        assertEquals(10, productMapper.selectById(milk.getId()).getStock());
        assertEquals(2L, countTestUserCartItems());
        assertTrue(findTestUserOrder().isEmpty());
    }


    //NOTE：测试订单能够成功创建且初始状态为待支付
    //NOTE: 测试两次非并发的相同requestId请求都能够成功
    @Test
    void createOrder_shouldCreateSuccessfully(){
        //准备数据
        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        insertCartItem(product, 5);

        CreateOrderDTO dto = TestDataFactory.createOrderDTO();

        CreateOrderVO first = orderService.createOrder(dto);
        CreateOrderVO second = orderService.createOrder(dto);

        assertEquals(first.getOrderId(), second.getOrderId());

        List<Order> orders = findTestUserOrder();
        assertEquals(1, orders.size());
        assertEquals(
                OrderStatusEnum.WAIT_PAY.getCode(),
                orders.get(0).getStatus()
        );
        assertEquals(dto.getRequestId(), orders.get(0).getRequestId());
        assertEquals(5, productMapper.selectById(product.getId()).getStock());

        List<OrderItem> orderItems = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderId, first.getOrderId())
        );
        assertEquals(1, orderItems.size());
        assertEquals(5, orderItems.get(0).getQuantity());
        assertEquals(product.getImageUrl(), orderItems.get(0).getProductPicture());
    }

    /// 临时测试：检验数据库唯一键是否真的存在
    @Test
    void createOrderTemplate(){
        Order order1 = TestDataFactory.createOrder(
                TEST_USER_ID, TEST_MERCHANT_ID, OrderStatusEnum.WAIT_PAY.getCode());

        order1.setRequestId("TEST_REQUEST");

        orderMapper.insert(order1);


        Order order2 = TestDataFactory.createOrder(
                TEST_USER_ID, TEST_MERCHANT_ID, OrderStatusEnum.WAIT_PAY.getCode());
        order2.setRequestId("TEST_REQUEST");

        assertThrows(
                DuplicateKeyException.class,
                () -> orderMapper.insert(order2)
        );
    }

    @Test
    void sameRequestId_shouldAllowOrdersForDifferentUsers() {
        String requestId = "SAME_REQUEST_DIFFERENT_USERS";
        Order firstUserOrder = TestDataFactory.createOrder(
                TEST_USER_ID, TEST_MERCHANT_ID, OrderStatusEnum.WAIT_PAY.getCode());
        Order secondUserOrder = TestDataFactory.createOrder(
                TEST_OTHER_USER_ID, TEST_MERCHANT_ID, OrderStatusEnum.WAIT_PAY.getCode());
        firstUserOrder.setRequestId(requestId);
        secondUserOrder.setRequestId(requestId);

        assertEquals(1, orderMapper.insert(firstUserOrder));
        assertEquals(1, orderMapper.insert(secondUserOrder));

        assertEquals(1L, orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, TEST_USER_ID)
                .eq(Order::getRequestId, requestId)));
        assertEquals(1L, orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, TEST_OTHER_USER_ID)
                .eq(Order::getRequestId, requestId)));
    }

    @Test
    void sameUniqueKey_secondInsertShouldFailAfterFirstTransactionCommits() throws Exception {
        String requestId = "LOW_LEVEL_COMMIT_REQUEST";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> secondInsert = null;

        try (Connection firstConnection = dataSource.getConnection()) {
            firstConnection.setAutoCommit(false);
            boolean firstTransactionFinished = false;
            try {
                assertEquals(1, insertOrder(firstConnection, "LOW_LEVEL_COMMIT_FIRST", requestId));

                CountDownLatch secondInsertStarted = new CountDownLatch(1);
                secondInsert = submitOrderInsert(
                        executor,
                        secondInsertStarted,
                        "LOW_LEVEL_COMMIT_SECOND",
                        requestId
                );

                assertTrue(secondInsertStarted.await(5, TimeUnit.SECONDS));
                assertSecondInsertIsBlocked(secondInsert);

                firstConnection.commit();
                firstTransactionFinished = true;

                Future<Integer> completedSecondInsert = secondInsert;
                ExecutionException exception = assertThrows(
                        ExecutionException.class,
                        () -> completedSecondInsert.get(5, TimeUnit.SECONDS)
                );
                assertInstanceOf(SQLIntegrityConstraintViolationException.class, exception.getCause());
                SQLException duplicateKey = (SQLException) exception.getCause();
                assertEquals("23000", duplicateKey.getSQLState());
                assertEquals(1062, duplicateKey.getErrorCode());
                assertEquals(1L, countOrdersByRequestId(requestId));
            } finally {
                if (!firstTransactionFinished) {
                    firstConnection.rollback();
                }
            }
        } finally {
            stopExecutor(executor, secondInsert);
        }
    }

    @Test
    void sameUniqueKey_secondInsertShouldSucceedAfterFirstTransactionRollsBack() throws Exception {
        String requestId = "LOW_LEVEL_ROLLBACK_REQUEST";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> secondInsert = null;

        try (Connection firstConnection = dataSource.getConnection()) {
            firstConnection.setAutoCommit(false);
            boolean firstTransactionFinished = false;
            try {
                assertEquals(1, insertOrder(firstConnection, "LOW_LEVEL_ROLLBACK_FIRST", requestId));

                CountDownLatch secondInsertStarted = new CountDownLatch(1);
                secondInsert = submitOrderInsert(
                        executor,
                        secondInsertStarted,
                        "LOW_LEVEL_ROLLBACK_SECOND",
                        requestId
                );

                assertTrue(secondInsertStarted.await(5, TimeUnit.SECONDS));
                assertSecondInsertIsBlocked(secondInsert);

                firstConnection.rollback();
                firstTransactionFinished = true;

                assertEquals(1, secondInsert.get(5, TimeUnit.SECONDS));
                assertEquals(1L, countOrdersByRequestId(requestId));
                assertEquals(
                        "LOW_LEVEL_ROLLBACK_SECOND",
                        jdbcTemplate.queryForObject(
                                "SELECT order_no FROM orders WHERE user_id = ? AND request_id = ?",
                                String.class,
                                TEST_USER_ID,
                                requestId
                        )
                );
            } finally {
                if (!firstTransactionFinished) {
                    firstConnection.rollback();
                }
            }
        } finally {
            stopExecutor(executor, secondInsert);
        }
    }

    //NOTE：测试并发创建订单时在相同的requestId仅生成一份订单和订单项并清空购物车
    @Test
    void createOrder_shouldCreateOnceForConcurrentSameRequestId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();


        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        AtomicReference<CreateOrderVO> firstResult =
                new AtomicReference<>();
        AtomicReference<CreateOrderVO> secondResult =
                new AtomicReference<>();


        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        insertCartItem(product,5);
        CreateOrderDTO sameRequest = TestDataFactory.createOrderDTO();
        Thread t1 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(TEST_USER_ID);
                latch.await();
                CreateOrderVO orderVO = orderService.createOrder(sameRequest);
                success.incrementAndGet();
                firstResult.set(orderVO);
            } catch (Throwable e) {
                firstFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(TEST_USER_ID);
                latch.await();
                CreateOrderVO orderVO = orderService.createOrder(sameRequest);
                success.incrementAndGet();
                secondResult.set(orderVO);
            } catch (Throwable e) {
                secondFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });

        t1.start();
        t2.start();

        latch.countDown();

        t1.join();
        t2.join();

        assertEquals(2, success.get());
        assertEquals(0, fail.get());

        assertNull(firstFailure.get());
        assertNull(secondFailure.get());

        assertEquals(
                firstResult.get().getOrderId(),
                secondResult.get().getOrderId()
        );

        List<Order> orders = findTestUserOrder();
        List<OrderItem> orderItem = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().
                eq(OrderItem::getProductId, product.getId()));
        List<CartItem> cartItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery().
                eq(CartItem::getUserId, TEST_USER_ID));

        assertEquals(1, orders.size());
        assertEquals(sameRequest.getRequestId(), orders.get(0).getRequestId());
        assertEquals(1,orderItem.size());
        assertEquals(5, orderItem.get(0).getQuantity());
        assertTrue(cartItems.isEmpty(), "购物车应该为空");
    }

    //NOTE：测试并发创建订单时在不同requestId的情况下仅生成一份订单和订单项并清空购物车
    @Test
    void createOrder_shouldCreateOnceForConcurrentDifferentRequestId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();


        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        AtomicReference<CreateOrderVO> firstResult =
                new AtomicReference<>();
        AtomicReference<CreateOrderVO> secondResult =
                new AtomicReference<>();


        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        insertCartItem(product,5);
        CreateOrderDTO firstRequest = TestDataFactory.createOrderDTO();
        CreateOrderDTO secondRequest = TestDataFactory.createOrderDTO();
        Thread t1 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(TEST_USER_ID);
                latch.await();
                CreateOrderVO orderVO = orderService.createOrder(firstRequest);
                success.incrementAndGet();
                firstResult.set(orderVO);
            } catch (Throwable e) {
                firstFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(TEST_USER_ID);
                latch.await();
                CreateOrderVO orderVO = orderService.createOrder(secondRequest);
                success.incrementAndGet();
                secondResult.set(orderVO);
            } catch (Throwable e) {
                secondFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });

        t1.start();
        t2.start();

        latch.countDown();

        t1.join();
        t2.join();

        assertEquals(1, success.get());
        assertEquals(1, fail.get());

        Throwable exception = firstFailure.get() == null ? secondFailure.get() : firstFailure.get();
        assertInstanceOf(BusinessException.class, exception);

        assertNotEquals(firstResult.get(), secondResult.get());


        List<Order> orders = findTestUserOrder();
        List<OrderItem> orderItem = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().
                eq(OrderItem::getProductId, product.getId()));
        List<CartItem> cartItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery().
                eq(CartItem::getUserId, TEST_USER_ID));

        assertEquals(1, orders.size());
        assertEquals(1,orderItem.size());
        assertEquals(5, orderItem.get(0).getQuantity());
        assertTrue(cartItems.isEmpty(), "购物车应该为空");
    }

    //NOTE：测试并发支付同一订单时仅一次成功并正确记录支付状态和时间
    @Test
    void payOrder_ConcurrentOrder() throws InterruptedException {
        insertMerchant();
        Order order = insertOrder(OrderStatusEnum.WAIT_PAY.getCode());

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Thread t1= new Thread(() -> {
            UserContextHolder.setUserId(TEST_USER_ID);
            try {
                latch.await();
                orderService.payOrder(order.getId());
                success.incrementAndGet();
            } catch (Throwable e) {
                firstFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });

        Thread t2= new Thread(() -> {
            UserContextHolder.setUserId(TEST_USER_ID);
            try {
                latch.await();
                orderService.payOrder(order.getId());
                success.incrementAndGet();
            } catch (Throwable e) {
                secondFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });

        t1.start();
        t2.start();

        latch.countDown();

        t1.join();
        t2.join();

        assertEquals(1, success.get());
        assertEquals(1, fail.get());
        assertExpectedConcurrentPayFailure(firstFailure.get(), secondFailure.get());

        Order paidOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.PAID.getCode(), paidOrder.getStatus());
        assertNotNull(paidOrder.getPayTime());
        assertEquals(1, orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, TEST_USER_ID)
                .isNotNull(Order::getPayTime)));
    }

    //NOTE：超过 30 分钟但尚未被定时任务取消的订单也不能支付
    @Test
    void payOrder_shouldRejectExpiredWaitingOrder() {
        insertMerchant();
        Order order = TestDataFactory.createOrder(
                TEST_USER_ID,
                TEST_MERCHANT_ID,
                OrderStatusEnum.WAIT_PAY.getCode()
        );
        order.setCreateTime(LocalDateTime.now().minusMinutes(31));
        orderMapper.insert(order);

        assertThrows(BusinessException.class, () -> orderService.payOrder(order.getId()));

        Order persistedOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), persistedOrder.getStatus());
        assertNull(persistedOrder.getPayTime());
    }

    //NOTE：测试并发完成同一订单时仅一次成功并正确记录完成状态和时间
    @Test
    void checkOrder_ConcurrentOrder() throws InterruptedException {
        insertMerchant();
        Order order = insertOrder(OrderStatusEnum.DELIVERED.getCode());

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Thread t1= new Thread(() -> {
            UserContextHolder.setUserId(TEST_USER_ID);
            try {
                latch.await();
                orderService.checkedOrder(order.getId());
                success.incrementAndGet();
            } catch (Throwable e) {
                firstFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }

        });

        Thread t2= new Thread(() -> {
            UserContextHolder.setUserId(TEST_USER_ID);
            try {
                latch.await();
                orderService.checkedOrder(order.getId());
                success.incrementAndGet();
            } catch (Throwable e) {
                secondFailure.set(e);
                fail.incrementAndGet();
            }finally {
                UserContextHolder.clear();
            }
        });

        t1.start();
        t2.start();
        latch.countDown();
        t1.join();
        t2.join();

        assertEquals(1, success.get());
        assertEquals(1, fail.get());

        Order finishOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.FINISHED.getCode(), finishOrder.getStatus());
        assertNotNull(finishOrder.getFinishTime());
        assertEquals(1, orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, TEST_USER_ID)
                .isNotNull(Order::getFinishTime)));

        Throwable failure = firstFailure.get() == null ? secondFailure.get() : firstFailure.get();
        assertInstanceOf(BusinessException.class, failure);

    }



    private void assertExpectedConcurrentCancelFailure(Throwable firstFailure, Throwable secondFailure) {
        Throwable failure = firstFailure == null ? secondFailure : firstFailure;
        assertInstanceOf(BusinessException.class, failure);
    }

    private void assertExpectedConcurrentPayFailure(Throwable firstFailure, Throwable secondFailure) {
        Throwable failure = firstFailure == null ? secondFailure : firstFailure;
        assertInstanceOf(BusinessException.class, failure);
    }

    private Future<Integer> submitOrderInsert(
            ExecutorService executor,
            CountDownLatch insertStarted,
            String orderNo,
            String requestId
    ) {
        return executor.submit(() -> {
            insertStarted.countDown();
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    int insertedRows = insertOrder(connection, orderNo, requestId);
                    connection.commit();
                    return insertedRows;
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        });
    }

    private int insertOrder(Connection connection, String orderNo, String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO orders (
                    order_no, user_id, request_id, merchant_id, merchant_name,
                    total_amount, status, receiver_name, receiver_phone, receiver_address,
                    original_amount, discount_amount
                )
                VALUES (?, ?, ?, ?, 'low-level-test-merchant',
                        1.00, ?, 'test-user', '13800000000', 'test-address',
                        1.00, 0.00)
                """)) {
            statement.setString(1, orderNo);
            statement.setLong(2, TEST_USER_ID);
            statement.setString(3, requestId);
            statement.setLong(4, TEST_MERCHANT_ID);
            statement.setInt(5, OrderStatusEnum.WAIT_PAY.getCode());
            return statement.executeUpdate();
        }
    }

    private void assertSecondInsertIsBlocked(Future<Integer> secondInsert) {
        assertThrows(
                TimeoutException.class,
                () -> secondInsert.get(500, TimeUnit.MILLISECONDS),
                "第一笔事务结束前，第二笔相同唯一键 INSERT 应等待唯一键冲突判定"
        );
    }

    private long countOrdersByRequestId(String requestId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND request_id = ?",
                Long.class,
                TEST_USER_ID,
                requestId
        );
        return count == null ? 0L : count;
    }

    private void stopExecutor(ExecutorService executor, Future<Integer> insert) throws InterruptedException {
        if (insert != null && !insert.isDone()) {
            insert.cancel(true);
        }
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private Product insertProduct(Long id, String name, int stock) {
        Product product = TestDataFactory.createProduct(id, name, stock, TEST_MERCHANT_ID);
        product.setCategoryId(TEST_CATEGORY_ID);
        insertCategory();
        productMapper.insert(product);
        return product;
    }

    private Order insertOrder(Integer status) {
        Order order = TestDataFactory.createOrder(TEST_USER_ID, TEST_MERCHANT_ID, status);
        orderMapper.insert(order);
        return order;
    }

    private  CartItem insertCartItem(Product product, Integer quantity) {
        CartItem cartItem = TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, product, quantity);
        cartMapper.insert(cartItem);
        return cartItem;
    }

    private Merchant insertMerchant() {
        Merchant openMerchant = TestDataFactory.createOpenMerchant(TEST_MERCHANT_ID);
        merchantMapper.insert(openMerchant);
        return openMerchant;
    }

    private void insertCategory() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO category (id, merchant_id, category_name, status, is_default)
                VALUES (?, ?, 'order_service_test_category', 0, 0)
                """, TEST_CATEGORY_ID, TEST_MERCHANT_ID);
    }

    private long countTestUserCartItems() {
        return cartMapper.selectCount(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, TEST_USER_ID));
    }

    private List<Order> findTestUserOrder() {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, TEST_USER_ID));
    }

    private void deleteTestData() {
        List<Long> orderIds = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
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
        // Product 使用逻辑删除；测试清理必须物理删除，才能安全复用固定主键。
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", TEST_MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM category WHERE merchant_id = ?", TEST_MERCHANT_ID);
        merchantMapper.deleteById(TEST_MERCHANT_ID);
    }
}
