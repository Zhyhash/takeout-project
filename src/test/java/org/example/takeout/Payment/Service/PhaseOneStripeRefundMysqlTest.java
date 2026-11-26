package org.example.takeout.Payment.Service;

import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Payment.Config.StripeRefundRecoveryTask;
import org.example.takeout.Payment.Config.StripeRefundRetryPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.RefundStatus;
import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundAttemptMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.Mapper.StripeRefundEventMapper;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** MySQL/InnoDB refund idempotency and recovery tests. All Stripe calls use a local fake. */
@SpringJUnitConfig(PhaseOneStripeRefundMysqlTest.TestConfig.class)
@TestPropertySource(properties = {
        "payment.stripe.enabled=true",
        "payment.stripe.refund.auto-retry-failure-reasons=phase_one_retryable"
})
@EnabledIfSystemProperty(named = "takeout.lab.enabled", matches = "true")
class PhaseOneStripeRefundMysqlTest {
    private static final long USER_ID = 9_701_001L;
    private static final long MERCHANT_ID = 9_702_001L;
    private static final long AMOUNT_MINOR = 12_345L;
    private static final String PAYMENT_INTENT_ID = "pi_phase_one_refund";
    private static final String REFUND_ID = "re_phase_one_refund";
    private static final int ITEM_QUANTITY = 3;
    private static final int STOCK_AFTER_ORDER = 7;
    private static final int STOCK_AFTER_REJECTION = 10;

    @Autowired private OrderCommandService orderCommands;
    @Autowired private StripeRefundService refundService;
    @Autowired private StripeRefundTransactionService refundTransactions;
    @Autowired private StripeRefundRecoveryTask refundRecoveryTask;
    @Autowired private OrderStripeRefundAttemptMapper attemptMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StripeRefundGateway refundGateway;

    private long orderId;
    private long productId;
    private long categoryId;
    private String orderNo;
    private String failureConstraintName;
    private boolean failureConstraintInstalled;
    private AtomicInteger requestCount;
    private AtomicInteger remoteRefundCount;
    private Map<String, StripeRefundSnapshot> remoteRefunds;
    private ConcurrentLinkedQueue<String> requestKeys;

    @BeforeEach
    void setUp() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals("takeout_integration_test", connection.getCatalog());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            return null;
        });
        orderId = uniqueId();
        productId = uniqueId();
        categoryId = uniqueId();
        orderNo = "phase-" + orderId;
        requestCount = new AtomicInteger();
        remoteRefundCount = new AtomicInteger();
        remoteRefunds = new ConcurrentHashMap<>();
        requestKeys = new ConcurrentLinkedQueue<>();
        reset(refundGateway);
        seedPaidOrder();
    }

    @AfterEach
    void cleanUpTestRows() {
        if (failureConstraintInstalled) {
            jdbc.execute("ALTER TABLE orders DROP CHECK " + failureConstraintName);
            failureConstraintInstalled = false;
        }
        jdbc.update("DELETE FROM stripe_refund_events WHERE refund_id IN "
                + "(SELECT refund_id FROM order_stripe_refund_attempts WHERE order_id = ?)", orderId);
        jdbc.update("DELETE FROM order_stripe_refund_attempts WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM order_stripe_refunds WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM order_stripe_payments WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM order_item WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM product WHERE id = ?", productId);
        jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    void remoteSuccessAndLateLocalFailureRecoverWithSameKeyAndReplayOnce() {
        installIdempotentFakeGateway();
        failureConstraintName = "chk_phase_one_refund_order_" + Long.toUnsignedString(orderId, 36);
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT " + failureConstraintName
                + " CHECK (order_no <> '" + orderNo + "' OR status <> "
                + OrderStatusEnum.REFUNDED.getCode() + ")");
        failureConstraintInstalled = true;

        // The stock and order rows are already in the post-rejection state when the after-commit refund runs.
        try {
            orderCommands.rejectOrderByMerchant(orderId, MERCHANT_ID);

            assertEquals(OrderStatusEnum.REFUNDING.getCode().longValue(), orderStatus());
            assertEquals(STOCK_AFTER_REJECTION, stock());
            assertEquals(RefundStatus.PENDING.code(), refundStatus());
            OrderStripeRefundAttempt stillPending = latestAttempt();
            assertNotNull(stillPending);
            assertNull(stillPending.getRefundId());
            assertNull(stillPending.getStripeStatus());
            assertEquals(ReconciliationState.PENDING.code(), stillPending.getReconciliationState());
            assertEquals(1, requestCount.get());
            assertEquals(1, remoteRefundCount.get());
            assertEquals(1, requestKeys.size());
            assertEquals(stillPending.getIdempotencyKey(), requestKeys.peek());
        } finally {
            jdbc.execute("ALTER TABLE orders DROP CHECK " + failureConstraintName);
            failureConstraintInstalled = false;
        }
        refundRecoveryTask.recoverPendingRefunds();

        assertEquals(OrderStatusEnum.REFUNDED.getCode().longValue(), orderStatus());
        assertEquals(RefundStatus.SUCCEEDED.code(), refundStatus());
        assertEquals(STOCK_AFTER_REJECTION, stock());
        OrderStripeRefundAttempt reconciled = latestAttempt();
        assertEquals(REFUND_ID, reconciled.getRefundId());
        assertEquals("succeeded", reconciled.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), reconciled.getReconciliationState());
        assertEquals(2, requestCount.get());
        assertEquals(1, remoteRefundCount.get());
        assertSameIdempotencyKey(reconciled.getIdempotencyKey());

        refundTransactions.syncRefundStatus(orderId, reconciled.getId(), succeededSnapshot());
        refundTransactions.syncRefundStatus(orderId, reconciled.getId(), succeededSnapshot());

        assertEquals(OrderStatusEnum.REFUNDED.getCode().longValue(), orderStatus());
        assertEquals(RefundStatus.SUCCEEDED.code(), refundStatus());
        assertEquals(STOCK_AFTER_REJECTION, stock());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_stripe_refunds WHERE order_id = ?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_stripe_refund_attempts WHERE order_id = ?", Integer.class, orderId));
    }

    @Test
    void normalRefundAndRecoveryRaceUseOneRemoteRefundAndOneLocalTransition() throws Exception {
        refundTransactions.prepareRefund(orderId, MERCHANT_ID);
        jdbc.update("UPDATE product SET stock = stock + ? WHERE id = ?", ITEM_QUANTITY, productId);
        OrderStripeRefundAttempt scannedAttempt = latestAttempt();
        assertNotNull(scannedAttempt);
        assertEquals(ReconciliationState.PENDING.code(), scannedAttempt.getReconciliationState());

        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch bothCallsStarted = new CountDownLatch(2);
        CountDownLatch releaseFakeStripe = new CountDownLatch(1);
        reset(refundGateway);
        installBlockingIdempotentFakeGateway(firstCallStarted, bothCallsStarted, releaseFakeStripe);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> normalRequest = executor.submit(() -> refundService.refundRejectedOrder(orderId));
            assertTrue(firstCallStarted.await(10, TimeUnit.SECONDS), "normal refund did not reach fake Stripe");

            Future<?> recovery = executor.submit(refundRecoveryTask::recoverPendingRefunds);
            assertTrue(bothCallsStarted.await(10, TimeUnit.SECONDS),
                    "recovery and normal request did not overlap at the fake gateway");
            releaseFakeStripe.countDown();
            normalRequest.get(15, TimeUnit.SECONDS);
            recovery.get(15, TimeUnit.SECONDS);
        } finally {
            releaseFakeStripe.countDown();
            executor.shutdownNow();
        }

        OrderStripeRefundAttempt reconciled = latestAttempt();
        assertEquals(OrderStatusEnum.REFUNDED.getCode().longValue(), orderStatus());
        assertEquals(RefundStatus.SUCCEEDED.code(), refundStatus());
        assertEquals(STOCK_AFTER_REJECTION, stock());
        assertEquals(REFUND_ID, reconciled.getRefundId());
        assertEquals("succeeded", reconciled.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), reconciled.getReconciliationState());
        assertEquals(2, requestCount.get(), "both contenders reached Stripe with the same durable key");
        assertEquals(1, remoteRefundCount.get(), "the fake remote idempotency ledger created one refund");
        assertSameIdempotencyKey(scannedAttempt.getIdempotencyKey());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_stripe_refunds WHERE order_id = ?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_stripe_refund_attempts WHERE order_id = ?", Integer.class, orderId));
    }

    private void seedPaidOrder() {
        jdbc.update("""
                INSERT INTO category (id, merchant_id, category_name, status, is_default)
                VALUES (?, ?, ?, 0, 0)
                """, categoryId, MERCHANT_ID, "phase-one-" + categoryId);
        jdbc.update("""
                INSERT INTO product (id, category_id, product_name, price, stock, stock_version,
                                     merchant_id, is_deleted, status, version)
                VALUES (?, ?, ?, 12.34, ?, 0, ?, 0, 0, 0)
                """, productId, categoryId, "phase-one-" + productId,
                STOCK_AFTER_ORDER, MERCHANT_ID);
        jdbc.update("""
                INSERT INTO orders (id, order_no, user_id, request_id, request_hash, merchant_id,
                                    merchant_name, total_amount, delivery_fee, status, receiver_name,
                                    receiver_phone, receiver_address, create_time,
                                    timeout_cancel_available_at, update_time, original_amount,
                                    discount_amount, pay_time, payment_started_at, merchant_seen)
                VALUES (?, ?, ?, ?, ?, ?, ?, 123.45, 0.00, ?, 'Test Customer', '13000000000',
                        'Test Address', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        123.45, 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, orderId, orderNo, USER_ID, "request-" + orderId,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                MERCHANT_ID, "Phase One Shop", OrderStatusEnum.PAID.getCode());
        jdbc.update("""
                INSERT INTO order_item (order_id, product_id, product_name, product_price, quantity,
                                        subtotal, stock_version, product_picture)
                VALUES (?, ?, 'Phase One Item', 12.34, ?, 37.02, 0, NULL)
                """, orderId, productId, ITEM_QUANTITY);
        jdbc.update("""
                INSERT INTO order_stripe_payments (order_id, payment_intent_id, amount_minor, currency,
                                                   stripe_status, idempotency_key, create_time, update_time,
                                                   reconciliation_state, retry_count, next_retry_at)
                VALUES (?, ?, ?, 'cny', 'succeeded', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        'RECONCILED', 0, NULL)
                """, orderId, PAYMENT_INTENT_ID + "_" + orderId, AMOUNT_MINOR,
                "phase-one-payment-" + orderId);
    }

    private void installIdempotentFakeGateway() {
        when(refundGateway.createRefund(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            requestCount.incrementAndGet();
            String key = invocation.getArgument(2);
            requestKeys.add(key);
            return remoteRefunds.computeIfAbsent(key, ignored -> {
                remoteRefundCount.incrementAndGet();
                return succeededSnapshot();
            });
        });
    }

    private void installBlockingIdempotentFakeGateway(CountDownLatch firstCallStarted,
                                                       CountDownLatch bothCallsStarted,
                                                       CountDownLatch releaseFakeStripe) {
        when(refundGateway.createRefund(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            requestCount.incrementAndGet();
            firstCallStarted.countDown();
            bothCallsStarted.countDown();
            assertTrue(releaseFakeStripe.await(10, TimeUnit.SECONDS),
                    "test did not release the fake Stripe response");
            String key = invocation.getArgument(2);
            requestKeys.add(key);
            return remoteRefunds.computeIfAbsent(key, ignored -> {
                remoteRefundCount.incrementAndGet();
                return succeededSnapshot();
            });
        });
    }

    private StripeRefundSnapshot succeededSnapshot() {
        OrderStripeRefundAttempt attempt = latestAttempt();
        return new StripeRefundSnapshot(REFUND_ID, PAYMENT_INTENT_ID + "_" + orderId,
                AMOUNT_MINOR, "cny", "succeeded", null,
                attempt == null ? null : attempt.getIdempotencyKey());
    }

    private void assertSameIdempotencyKey(String expectedKey) {
        assertEquals(2, requestKeys.size());
        assertEquals(expectedKey, requestKeys.peek());
        assertTrue(requestKeys.stream().allMatch(expectedKey::equals));
    }

    private long orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", Long.class, orderId);
    }

    private String refundStatus() {
        return jdbc.queryForObject("SELECT status FROM order_stripe_refunds WHERE order_id = ?",
                String.class, orderId);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId);
    }

    private OrderStripeRefundAttempt latestAttempt() {
        return attemptMapper.selectLatestAttemptByOrderId(orderId);
    }

    private static long uniqueId() {
        return java.util.UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableConfigurationProperties(StripeRefundRetryPolicy.class)
    @Import({MybatisPlusConfig.class, StripeRefundTransactionService.class,
            StripeRefundService.class, StripeRefundRecoveryTask.class})
    @MapperScan(basePackageClasses = {OrderStripePaymentMapper.class, OrderStripeRefundMapper.class,
            OrderStripeRefundAttemptMapper.class, StripeRefundEventMapper.class,
            OrderMapper.class, OrderItemMapper.class, ProductMapper.class},
            annotationClass = Mapper.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            String port = System.getProperty("takeout.test.mysql.port");
            if (port == null || port.isBlank()) {
                throw new IllegalStateException("takeout.test.mysql.port must target the isolated test container");
            }
            String url = "jdbc:mysql://127.0.0.1:" + port + "/takeout_integration_test"
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8&characterEncoding=UTF-8";
            return new DriverManagerDataSource(url, "root", "root");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

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
                                          ObjectProvider<StripeRefundService> refundServiceProvider,
                                          @Qualifier("directRefundTestExecutor") TaskExecutor executor) {
            return new OrderCommandService(orderMapper, orderItems, refundServiceProvider, executor);
        }

        @Bean(name = "directRefundTestExecutor")
        TaskExecutor directRefundTestExecutor() {
            return Runnable::run;
        }
    }
}
