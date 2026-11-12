package org.example.takeout.integration;

import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Order.Config.OrderAcceptTimeoutTask;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Payment.Config.StripeRefundRecoveryTask;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Gateway.StripeGatewayException;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.Service.StripeRefundService;
import org.example.takeout.Payment.Service.StripeRefundTransactionService;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductCommandService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家超时未接单自动取消的真实 MySQL 回归测试。
 *
 * <p>接单与超时取消的时间限制写在 SQL 里（{@code pay_time ... NOW() - INTERVAL 10 MINUTE}），
 * 用 MySQL 专有语法表达，因此这批用例必须跑真实 MySQL：H2 解析不了 MySQL 的 INTERVAL 字面量，
 * 在这上面用 H2 断言等于没测到真正的抢占条件。每个类运行独占一个随机库，不碰任何既有数据。</p>
 *
 * <p>连接信息可通过 {@code takeout.accept-timeout-test.mysql.host/port/user/password} 覆盖，
 * 默认 localhost/3306/root/root。</p>
 */
@SpringJUnitConfig(OrderAcceptTimeoutIntegrationTest.TestConfig.class)
@TestPropertySource(properties = "payment.stripe.enabled=true")
class OrderAcceptTimeoutIntegrationTest {

    private static final long ORDER_ID = 811L;
    private static final long USER_ID = 311L;
    private static final long MERCHANT_ID = 411L;
    private static final long OTHER_MERCHANT_ID = 412L;
    private static final long PRODUCT_ID = 911L;
    private static final long ORDER_ITEM_ID = 1L;

    private static final String PAYMENT_INTENT_ID = "pi_accept_timeout_811";
    private static final String REFUND_ID = "re_accept_timeout_811";
    private static final long AMOUNT_MINOR = 12_345L;
    private static final String CURRENCY = "cny";
    private static final String IDEMPOTENCY_KEY = "order_refund_" + ORDER_ID;

    private static final int STOCK_AFTER_ORDER = 7;
    private static final int ITEM_QUANTITY = 3;
    private static final int STOCK_AFTER_REFUND = STOCK_AFTER_ORDER + ITEM_QUANTITY;

    private static final int WITHIN_WINDOW_MINUTES = 2;
    private static final int BEYOND_WINDOW_MINUTES = 11;

    private static final TestDatabase DATABASE = TestDatabase.create();

    @Autowired private OrderCommandService orderCommands;
    @Autowired private OrderAcceptTimeoutTask acceptTimeoutTask;
    @Autowired private StripeRefundRecoveryTask refundRecoveryTask;
    @Autowired private StripeRefundGateway refundGateway;
    @Autowired private OrderStripeRefundMapper refundMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private JdbcTemplate jdbc;

    @AfterAll
    static void dropOwnedDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void setUp() {
        reset(refundGateway);
        recreateSchema();
    }

    // ------------------------------------------------------------------
    // 接单的 10 分钟窗口
    // ------------------------------------------------------------------

    @Test
    void merchantCanAcceptWithinTenMinuteWindow() {
        givenPaidOrder(OrderStatusEnum.PAID.getCode(), WITHIN_WINDOW_MINUTES);

        assertDoesNotThrow(() -> orderCommands.acceptOrderByMerchant(ORDER_ID, MERCHANT_ID));

        assertEquals(OrderStatusEnum.PREPARING.getCode(), orderStatus());
        assertTrue(pendingUnacceptedOrderIds().isEmpty(),
                "窗口内订单不应出现在超时候选里");
    }

    @Test
    void merchantCannotAcceptAfterTenMinuteWindow() {
        givenPaidOrder(OrderStatusEnum.PAID.getCode(), BEYOND_WINDOW_MINUTES);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderCommands.acceptOrderByMerchant(ORDER_ID, MERCHANT_ID));

        assertEquals("订单已超过商家接单时限，将被系统自动取消并退款", failure.getMessage());
        assertEquals(OrderStatusEnum.PAID.getCode(), orderStatus());
    }

    /** 商家主动拒单不受 10 分钟限制：刚支付的订单也能立刻拒单退款。 */
    @Test
    void merchantRejectIsNotBoundByTheTenMinuteWindow() {
        givenPaidOrder(OrderStatusEnum.PAID.getCode(), WITHIN_WINDOW_MINUTES);
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));

        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(REFUND_ID, refundRecord().getRefundId());
        assertEquals(STOCK_AFTER_REFUND, stock());
    }

    // ------------------------------------------------------------------
    // 超时自动取消
    // ------------------------------------------------------------------

    @Test
    void timeoutTaskCancelsUnacceptedOrderAndRefundsOnce() {
        givenPaidOrder(OrderStatusEnum.PAID.getCode(), BEYOND_WINDOW_MINUTES);
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));

        acceptTimeoutTask.cancelUnacceptedOrders();

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        OrderStripeRefund refund = refundRecord();
        assertNotNull(refund);
        assertEquals(REFUND_ID, refund.getRefundId());
        assertEquals("succeeded", refund.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refund.getReconciliationState());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());
        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
    }

    @Test
    void timeoutTaskIgnoresOrderMerchantAlreadyAccepted() {
        givenPaidOrder(OrderStatusEnum.PREPARING.getCode(), BEYOND_WINDOW_MINUTES);

        acceptTimeoutTask.cancelUnacceptedOrders();

        assertEquals(OrderStatusEnum.PREPARING.getCode(), orderStatus());
        assertEquals(0, refundRowCount());
        assertEquals(STOCK_AFTER_ORDER, stock());
        verify(refundGateway, never()).createRefund(anyString(), eq(AMOUNT_MINOR), anyString());
    }

    @Test
    void repeatedTimeoutTaskDoesNotRefundAgain() {
        givenPaidOrder(OrderStatusEnum.PAID.getCode(), BEYOND_WINDOW_MINUTES);
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));

        acceptTimeoutTask.cancelUnacceptedOrders();
        acceptTimeoutTask.cancelUnacceptedOrders();
        acceptTimeoutTask.cancelUnacceptedOrders();

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());
        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
    }

    /**
     * 接单和超时取消同时发生：两条路径都以 {@code status = PAID} 作为抢占条件，
     * 由 InnoDB 行锁串行化，所以恰好一方成功。
     */
    @Test
    void acceptAndTimeoutCancelRaceLetsOnlyOneSideWin() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                recreateSchema();
                reset(refundGateway);
                givenPaidOrder(OrderStatusEnum.PAID.getCode(), BEYOND_WINDOW_MINUTES);
                when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                        .thenReturn(snapshot("succeeded"));

                CyclicBarrier startLine = new CyclicBarrier(2);
                Future<Boolean> acceptWon = executor.submit(race(startLine,
                        () -> orderCommands.acceptOrderByMerchant(ORDER_ID, MERCHANT_ID)));
                Future<Boolean> cancelWon = executor.submit(race(startLine,
                        () -> orderCommands.cancelOrderByMerchantTimeout(ORDER_ID)));

                boolean acceptSucceeded = acceptWon.get(20, TimeUnit.SECONDS);
                boolean cancelSucceeded = cancelWon.get(20, TimeUnit.SECONDS);

                assertNotEquals(acceptSucceeded, cancelSucceeded,
                        "接单与超时取消必须互斥，round=" + round);
                if (cancelSucceeded) {
                    assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
                    assertEquals(STOCK_AFTER_REFUND, stock());
                    assertEquals(1, refundRowCount());
                } else {
                    assertEquals(OrderStatusEnum.PREPARING.getCode(), orderStatus());
                    assertEquals(0, refundRowCount());
                    assertEquals(STOCK_AFTER_ORDER, stock());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 退款请求失败/结果未知时不能把订单退回 PAID：订单停在 REFUNDING、退款记录保持 PENDING，
     * 由现有恢复任务用同一幂等键重新收敛，不会产生第二笔退款。
     */
    @Test
    void refundFailureKeepsOrderRefundingForExistingRecovery() {
        givenPaidOrder(OrderStatusEnum.PAID.getCode(), BEYOND_WINDOW_MINUTES);
        // 第一次调用结果未知（本地无法判断 Stripe 是否已退款），恢复任务重试时才拿到确定结果
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenThrow(new StripeGatewayException(StripeGatewayException.Kind.OUTCOME_UNKNOWN))
                .thenReturn(snapshot("succeeded"));

        acceptTimeoutTask.cancelUnacceptedOrders();

        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        OrderStripeRefund pending = refundRecord();
        assertNotNull(pending);
        assertNull(pending.getRefundId());
        assertEquals(ReconciliationState.PENDING.code(), pending.getReconciliationState());
        // 库存在取消事务里已经归还并提交，不会因为退款失败而回滚
        assertEquals(STOCK_AFTER_REFUND, stock());

        refundRecoveryTask.recoverPendingRefunds();

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        OrderStripeRefund refunded = refundRecord();
        assertEquals(REFUND_ID, refunded.getRefundId());
        assertEquals("succeeded", refunded.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refunded.getReconciliationState());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());
        verify(refundGateway, times(2)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
    }

    // ------------------------------------------------------------------
    // 数据准备与断言辅助
    // ------------------------------------------------------------------

    private Callable<Boolean> race(CyclicBarrier startLine, Runnable action) {
        return () -> {
            startLine.await(20, TimeUnit.SECONDS);
            try {
                action.run();
                return true;
            } catch (BusinessException expectedLoser) {
                return false;
            }
        };
    }

    private void givenPaidOrder(int status, int paidMinutesAgo) {
        jdbc.update("""
                INSERT INTO orders (
                    id, order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                    total_amount, status, receiver_name, receiver_phone, receiver_address,
                    create_time, timeout_cancel_available_at, update_time, pay_time
                ) VALUES (?, 'ORD-811', ?, 'request-811', 'fingerprint', ?, '测试商家',
                          123.45, ?, '测试用户', '13000000000', '测试地址',
                          NOW(), DATE_ADD(NOW(), INTERVAL 30 MINUTE), NOW(),
                          DATE_SUB(NOW(), INTERVAL ? MINUTE))
                """, ORDER_ID, USER_ID, MERCHANT_ID, status, paidMinutesAgo);

        jdbc.update("""
                INSERT INTO order_stripe_payments (
                    order_id, payment_intent_id, amount_minor, currency, stripe_status,
                    idempotency_key, create_time, update_time, reconciliation_state
                ) VALUES (?, ?, ?, 'cny', 'succeeded', ?, NOW(), NOW(), 'COMPLETE')
                """, ORDER_ID, PAYMENT_INTENT_ID, AMOUNT_MINOR, "order_pay_" + ORDER_ID);

        jdbc.update("""
                INSERT INTO product (id, stock, stock_version, version, status, is_deleted)
                VALUES (?, ?, 0, 0, 0, 0)
                """, PRODUCT_ID, STOCK_AFTER_ORDER);
        jdbc.update("""
                INSERT INTO order_item (
                    id, order_id, product_id, product_name, product_price, quantity, subtotal,
                    stock_version, product_picture
                ) VALUES (?, ?, ?, '测试商品', 12.34, ?, 37.02, 0, NULL)
                """, ORDER_ITEM_ID, ORDER_ID, PRODUCT_ID, ITEM_QUANTITY);
    }

    /** 走与被测代码相同的扫描 SQL，避免测试自己再写一份时间条件。 */
    private List<Long> pendingUnacceptedOrderIds() {
        return orderMapper.selectUnacceptedPaidOrderIds(OrderStatusEnum.PAID.getCode(), 100);
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", Integer.class, ORDER_ID);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, PRODUCT_ID);
    }

    private int refundRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_stripe_refunds", Integer.class);
    }

    private OrderStripeRefund refundRecord() {
        return refundMapper.selectRefundmentByOrderId(ORDER_ID);
    }

    private static StripeRefundSnapshot snapshot(String status) {
        return new StripeRefundSnapshot(REFUND_ID, PAYMENT_INTENT_ID, AMOUNT_MINOR, CURRENCY, status);
    }

    private void recreateSchema() {
        jdbc.execute("DROP TABLE IF EXISTS order_stripe_refunds");
        jdbc.execute("DROP TABLE IF EXISTS order_stripe_payments");
        jdbc.execute("DROP TABLE IF EXISTS order_item");
        jdbc.execute("DROP TABLE IF EXISTS product");
        jdbc.execute("DROP TABLE IF EXISTS orders");

        jdbc.execute("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    order_no VARCHAR(32) NOT NULL,
                    user_id BIGINT NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    request_hash VARCHAR(64) NOT NULL,
                    merchant_id BIGINT NOT NULL,
                    merchant_name VARCHAR(255) NOT NULL,
                    total_amount DECIMAL(10, 2) NOT NULL,
                    delivery_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    status INT NOT NULL,
                    cancel_source INT NULL,
                    receiver_name VARCHAR(64) NOT NULL,
                    receiver_phone VARCHAR(32) NOT NULL,
                    receiver_address VARCHAR(255) NOT NULL,
                    remark VARCHAR(255) NULL,
                    create_time DATETIME NOT NULL,
                    timeout_cancel_available_at DATETIME NOT NULL,
                    update_time DATETIME NOT NULL,
                    finish_time DATETIME NULL,
                    original_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    pay_time DATETIME NULL,
                    merchant_seen TINYINT(1) NOT NULL DEFAULT 0
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE order_stripe_payments (
                    order_id BIGINT PRIMARY KEY,
                    payment_intent_id VARCHAR(255) NULL,
                    amount_minor BIGINT NOT NULL,
                    currency VARCHAR(10) NOT NULL,
                    stripe_status VARCHAR(64) NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    create_time DATETIME NOT NULL,
                    update_time DATETIME NOT NULL,
                    reconciliation_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_at DATETIME NULL,
                    UNIQUE KEY uk_payment_intent (payment_intent_id),
                    UNIQUE KEY uk_payment_idempotency (idempotency_key)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE order_stripe_refunds (
                    order_id BIGINT PRIMARY KEY,
                    refund_id VARCHAR(255) NULL,
                    amount_minor BIGINT NOT NULL,
                    currency VARCHAR(10) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    stripe_status VARCHAR(32) NULL,
                    reconciliation_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_at DATETIME NULL,
                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_refund_id (refund_id),
                    UNIQUE KEY uk_refund_idempotency (idempotency_key)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
        jdbc.execute("CREATE TABLE product (id BIGINT PRIMARY KEY, stock INT NULL, stock_version INT NULL, "
                + "version INT NULL, status INT NULL, is_deleted INT NULL) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4");
        jdbc.execute("CREATE TABLE order_item (id BIGINT PRIMARY KEY, order_id BIGINT NULL, "
                + "product_id BIGINT NULL, product_name VARCHAR(100) NULL, product_price DECIMAL(10,2) NULL, "
                + "quantity INT NULL, subtotal DECIMAL(10,2) NULL, stock_version INT NULL, "
                + "product_picture VARCHAR(100) NULL) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4");
    }

    /** 每个类独占一个随机库，测试结束即删除，不会碰到已有的应用库或测试库。 */
    private static final class TestDatabase implements AutoCloseable {
        private final String name;
        private final String serverUrl;
        private final String jdbcUrl;
        private final String user;
        private final String password;

        private TestDatabase(String name, String serverUrl, String jdbcUrl, String user, String password) {
            this.name = name;
            this.serverUrl = serverUrl;
            this.jdbcUrl = jdbcUrl;
            this.user = user;
            this.password = password;
        }

        private static TestDatabase create() {
            String host = System.getProperty("takeout.accept-timeout-test.mysql.host", "localhost");
            String port = System.getProperty("takeout.accept-timeout-test.mysql.port", "3306");
            String user = System.getProperty("takeout.accept-timeout-test.mysql.user", "root");
            String password = System.getProperty("takeout.accept-timeout-test.mysql.password", "root");
            if (!host.matches("[a-zA-Z0-9.\\-\\[\\]:]+") || !port.matches("[0-9]{1,5}")) {
                throw new IllegalArgumentException("测试库地址不能包含 JDBC 路径或参数");
            }
            String name = "takeout_accept_timeout_" + UUID.randomUUID().toString().replace("-", "");
            String options = "?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=GMT%2B8&characterEncoding=UTF-8";
            String serverUrl = "jdbc:mysql://" + host + ":" + port + "/" + options;
            String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + name + options;
            try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + name
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            } catch (SQLException failure) {
                throw new IllegalStateException("无法创建测试库，请确认本地 MySQL 可用", failure);
            }
            return new TestDatabase(name, serverUrl, jdbcUrl, user, password);
        }

        private DataSource dataSource() {
            return new DriverManagerDataSource(jdbcUrl, user, password);
        }

        @Override
        public void close() {
            try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
            } catch (SQLException ignored) {
                // 清理失败不影响测试结论
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = {OrderStripePaymentMapper.class, OrderStripeRefundMapper.class,
            OrderMapper.class, OrderItemMapper.class, ProductMapper.class},
            annotationClass = Mapper.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return DATABASE.dataSource();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /** 唯一的假实现：只替换 Stripe HTTP，其它全是真实 SQL 与真实事务。 */
        @Bean
        StripeRefundGateway stripeRefundGateway() {
            return mock(StripeRefundGateway.class);
        }

        @Bean
        OrderItemService orderItems(ProductMapper productMapper, OrderItemMapper itemMapper) {
            return new OrderItemService(new ProductCommandService(productMapper, null), itemMapper, null);
        }

        @Bean
        OrderCommandService orderCommands(OrderMapper orderMapper, OrderItemService orderItems,
                                          ObjectProvider<StripeRefundService> refundServiceProvider) {
            // 同步执行器：afterCommit 里的 Stripe 调用在当前线程跑完，断言无需等待
            return new OrderCommandService(orderMapper, orderItems, refundServiceProvider, Runnable::run);
        }

        @Bean
        StripeRefundTransactionService refundTransactionService(OrderStripeRefundMapper refundMapper,
                                                                OrderMapper orderMapper,
                                                                OrderStripePaymentMapper paymentMapper) {
            return new StripeRefundTransactionService(refundMapper, orderMapper, paymentMapper);
        }

        @Bean
        StripeRefundService stripeRefundService(StripeRefundTransactionService transactions,
                                                StripeRefundGateway gateway,
                                                OrderStripePaymentMapper paymentMapper,
                                                OrderStripeRefundMapper refundMapper) {
            return new StripeRefundService(transactions, gateway, paymentMapper, refundMapper);
        }

        @Bean
        StripeRefundRecoveryTask refundRecoveryTask(OrderStripeRefundMapper refundMapper,
                                                    StripeRefundService refundService,
                                                    StripeRefundTransactionService refundTransactions) {
            return new StripeRefundRecoveryTask(refundMapper, refundService, refundTransactions);
        }

        @Bean
        OrderAcceptTimeoutTask acceptTimeoutTask(OrderMapper orderMapper, OrderCommandService orderCommands) {
            return new OrderAcceptTimeoutTask(orderMapper, orderCommands);
        }
    }
}
