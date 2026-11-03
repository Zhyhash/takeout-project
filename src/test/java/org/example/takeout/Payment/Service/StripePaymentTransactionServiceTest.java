package org.example.takeout.Payment.Service;

import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.example.takeout.Order.Domain.DeliveryFeeCalculator;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Payment.Config.StripePaymentProperties;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.example.takeout.Payment.Gateway.StripePaymentEvent;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.StripePaymentEventMapper;
import org.example.takeout.Payment.VO.StripePaymentView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2/MyBatis transactions verify payment-event idempotency and the existing order CAS. */
@SpringJUnitConfig(StripePaymentTransactionServiceTest.TestConfig.class)
@TestPropertySource(properties = "payment.stripe.enabled=true")
class StripePaymentTransactionServiceTest {
    private static final long ORDER_ID = 501L;
    private static final long USER_ID = 101L;
    private static final long MERCHANT_ID = 201L;
    private static final String INTENT_ID = "pi_test_takeout_501";

    @Autowired private StripePaymentTransactionService transactions;
    @Autowired private OrderStripePaymentMapper orderPaymentMapper;
    @Autowired private StripePaymentEventMapper paymentEventMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderService orderService;
    @Autowired private MerchantSseService merchantSseService;
    @Autowired private StripePaymentGateway gateway;
    @Autowired private StripePaymentService payments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        reset(merchantSseService, gateway);
        UserContextHolder.setUserId(USER_ID);
        jdbc.execute("DROP TABLE IF EXISTS stripe_payment_events");
        jdbc.execute("DROP TABLE IF EXISTS order_stripe_payments");
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
                    cancel_source INT,
                    receiver_name VARCHAR(64) NOT NULL,
                    receiver_phone VARCHAR(32) NOT NULL,
                    receiver_address VARCHAR(255) NOT NULL,
                    remark VARCHAR(255),
                    create_time TIMESTAMP NOT NULL,
                    timeout_cancel_available_at TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL,
                    finish_time TIMESTAMP,
                    original_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    pay_time TIMESTAMP,
                    merchant_seen BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE order_stripe_payments (
                    order_id BIGINT PRIMARY KEY,
                    payment_intent_id VARCHAR(255) UNIQUE,
                    amount_minor BIGINT NOT NULL,
                    currency VARCHAR(3) NOT NULL,
                    stripe_status VARCHAR(64),
                    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE stripe_payment_events (
                    event_id VARCHAR(255) PRIMARY KEY,
                    payment_intent_id VARCHAR(255) NOT NULL,
                    event_type VARCHAR(100) NOT NULL,
                    create_time TIMESTAMP NOT NULL
                )
                """);
        LocalDateTime created = LocalDateTime.now().minusMinutes(1);
        jdbc.update("""
                INSERT INTO orders (
                    id, order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                    total_amount, status, receiver_name, receiver_phone, receiver_address,
                    create_time, timeout_cancel_available_at, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ORDER_ID, "ORD-501", USER_ID, "request-501", "fingerprint", MERCHANT_ID,
                "Test shop", 123.45, OrderStatusEnum.WAIT_PAY.getCode(), "Test user", "13000000000",
                "Test address", created, created.plusMinutes(30), created);
        jdbc.update("""
                INSERT INTO order_stripe_payments (
                    order_id, payment_intent_id, amount_minor, currency, stripe_status,
                    idempotency_key, create_time, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, ORDER_ID, INTENT_ID, 12_345L, "cny", "requires_payment_method", "test-attempt-501");
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void successfulWebhookUpdatesExistingOrderAndNotifiesMerchantOnlyOnce() {
        StripePaymentSnapshot paid = snapshot("succeeded", 12_345L);

        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_success_1", "payment_intent.succeeded", false, paid));
        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_success_1", "payment_intent.succeeded", false, paid));
        // A distinct Stripe event for the same intent must also be harmless to the order CAS.
        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_success_2", "payment_intent.succeeded", false, paid));

        Order savedOrder = orderMapper.selectById(ORDER_ID);
        assertEquals(OrderStatusEnum.PAID.getCode(), savedOrder.getStatus());
        assertNotNull(savedOrder.getPayTime());
        assertEquals("succeeded", orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getStripeStatus());
        assertEquals(2, paymentEventMapper.countEventsByEventId("evt_success_1") + paymentEventMapper.countEventsByEventId("evt_success_2"));
        verify(merchantSseService, times(1)).notifyNewOrder(MERCHANT_ID);
        verifyNoMoreInteractions(merchantSseService);
    }

    @Test
    void failedPaymentCanBeRetriedAndAnOlderFailureCannotUndoSuccess() {
        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_failed", "payment_intent.payment_failed", false, snapshot("requires_payment_method", 0L)));
        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        verifyNoInteractions(merchantSseService);

        StripePaymentSnapshot paid = snapshot("succeeded", 12_345L);
        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_success", "payment_intent.succeeded", false, paid));
        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_delayed_failure", "payment_intent.payment_failed", false, snapshot("requires_payment_method", 0L)));

        assertEquals(OrderStatusEnum.PAID.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertEquals("succeeded", orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getStripeStatus());
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
        verifyNoMoreInteractions(merchantSseService);
    }

    @Test
    void canceledOrderIsNotRevivedByAValidLateSuccess() {
        jdbc.update("UPDATE orders SET status = ?, cancel_source = 1 WHERE id = ?",
                OrderStatusEnum.CANCELLED.getCode(), ORDER_ID);

        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_late_success", "payment_intent.succeeded", false, snapshot("succeeded", 12_345L)));

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertNull(orderMapper.selectById(ORDER_ID).getPayTime());
        assertEquals("succeeded", orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getStripeStatus());
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void expiredWaitingOrderRecordsStripeSuccessWithoutAdvancingOrNotifying() {
        jdbc.update("UPDATE orders SET create_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(31), ORDER_ID);

        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_expired_success", "payment_intent.succeeded", false, snapshot("succeeded", 12_345L)));

        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertNull(orderMapper.selectById(ORDER_ID).getPayTime());
        assertEquals("succeeded", orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getStripeStatus());
        assertEquals(1, paymentEventMapper.countEventsByEventId("evt_expired_success"));
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void webhookAndActiveStatusQueryConvergeOnOneOrderTransition() throws Exception {
        StripePaymentSnapshot paid = snapshot("succeeded", 12_345L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> webhook = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                        "evt_concurrent_success", "payment_intent.succeeded", false, paid));
                return null;
            });
            Future<?> activeSync = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                transactions.syncPaymentStatusFromSnapshot(paid);
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            webhook.get(10, TimeUnit.SECONDS);
            activeSync.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(OrderStatusEnum.PAID.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertEquals(1, paymentEventMapper.countEventsByEventId("evt_concurrent_success"));
        verify(merchantSseService, times(1)).notifyNewOrder(MERCHANT_ID);
        verifyNoMoreInteractions(merchantSseService);
    }

    @Test
    void simultaneousPreparationReusesThePersistedIdempotencyKey() throws Exception {
        jdbc.update("DELETE FROM order_stripe_payments WHERE order_id = ?", ORDER_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OrderStripePayment> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return transactions.getOrCreateOrderPaymentRecord(ORDER_ID, USER_ID, 12_345L);
            });
            Future<OrderStripePayment> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return transactions.getOrCreateOrderPaymentRecord(ORDER_ID, USER_ID, 12_345L);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            OrderStripePayment preparedFirst = first.get(10, TimeUnit.SECONDS);
            OrderStripePayment preparedSecond = second.get(10, TimeUnit.SECONDS);
            assertEquals(preparedFirst.getIdempotencyKey(), preparedSecond.getIdempotencyKey());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_stripe_payments", Integer.class));
        assertEquals(12_345L, orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getAmountMinor());
    }

    @Test
    void legacySimulationEndpointIsRejectedWhenStripeIsEnabled() {
        assertThrows(BusinessException.class, () -> orderService.payOrder(ORDER_ID));

        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void stripeNetworkCallSuspendsAnAmbientDatabaseTransaction() {
        jdbc.update("DELETE FROM order_stripe_payments WHERE order_id = ?", ORDER_ID);
        AtomicBoolean networkCallInsideTransaction = new AtomicBoolean(true);
        StripePaymentSnapshot created = snapshot("requires_payment_method", 0L);
        when(gateway.createPaymentIntent(eq(12_345L), eq(String.valueOf(ORDER_ID)), anyString()))
                .thenAnswer(invocation -> {
                    networkCallInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return created;
                });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            payments.createOrReuseOrderPaymentIntent(ORDER_ID);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        });

        assertFalse(networkCallInsideTransaction.get());
        assertEquals("pi_test_takeout_501", orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getPaymentIntentId());
        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        verify(gateway).createPaymentIntent(eq(12_345L), eq(String.valueOf(ORDER_ID)), anyString());
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void paymentAndEventReceiptRollBackTogetherAndCanBeRetried() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                            "evt_retry", "payment_intent.succeeded", false, snapshot("succeeded", 12_345L)));
                    throw new IllegalStateException("force rollback");
                }));

        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertEquals("requires_payment_method", orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getStripeStatus());
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_retry"));
        verifyNoInteractions(merchantSseService);

        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_retry", "payment_intent.succeeded", false, snapshot("succeeded", 12_345L)));
        assertEquals(OrderStatusEnum.PAID.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
        verifyNoMoreInteractions(merchantSseService);
    }

    @Test
    void mismatchedAmountCurrencyOrOrderReferenceIsRejectedBeforePaymentConfirmation() {
        assertThrows(RuntimeException.class, () ->
                transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                        "evt_wrong_amount", "payment_intent.succeeded", false, new StripePaymentSnapshot(INTENT_ID, 12_346L, 12_346L,
                        "cny", "succeeded", false, String.valueOf(ORDER_ID), null))));
        assertThrows(RuntimeException.class, () ->
                transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                        "evt_wrong_currency", "payment_intent.succeeded", false, new StripePaymentSnapshot(INTENT_ID, 12_345L, 12_345L,
                        "usd", "succeeded", false, String.valueOf(ORDER_ID), null))));
        assertThrows(RuntimeException.class, () ->
                transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                        "evt_wrong_order", "payment_intent.succeeded", false, new StripePaymentSnapshot(INTENT_ID, 12_345L, 12_345L,
                        "cny", "succeeded", false, "other-order", null))));

        assertEquals(OrderStatusEnum.WAIT_PAY.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stripe_payment_events", Integer.class));
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void paymentIntentBindingFailureCanBeRetriedWithTheSameIdempotencyKey() {
        jdbc.update(
                "DELETE FROM order_stripe_payments WHERE order_id = ?",
                ORDER_ID
        );
        jdbc.execute("""
                ALTER TABLE order_stripe_payments
                ADD CONSTRAINT chk_f01_reject_attach
                CHECK (
                    payment_intent_id IS NULL
                    OR payment_intent_id <> 'pi_f01_attach'
                )
                """);
        StripePaymentSnapshot created =
                new StripePaymentSnapshot(
                        "pi_f01_attach",
                        12_345L,
                        0L,
                        "cny",
                        "requires_payment_method",
                        false,
                        String.valueOf(ORDER_ID),
                        "secret_f01"
                );
        when(gateway.createPaymentIntent(
                eq(12_345L),
                eq(String.valueOf(ORDER_ID)),
                anyString()
        )).thenReturn(created);
        assertThrows(
                RuntimeException.class,
                () -> payments.createOrReuseOrderPaymentIntent(ORDER_ID)
        );

        OrderStripePayment saved =
                orderPaymentMapper.selectPaymentByOrderId(ORDER_ID);

        assertNotNull(saved);
        assertNull(saved.getPaymentIntentId());
        assertNotNull(saved.getIdempotencyKey());

        verify(gateway, times(1)).createPaymentIntent(
                eq(12_345L),
                eq(String.valueOf(ORDER_ID)),
                anyString()
        );

        jdbc.execute("ALTER TABLE order_stripe_payments DROP CONSTRAINT chk_f01_reject_attach");

        StripePaymentView recovered = payments.createOrReuseOrderPaymentIntent(ORDER_ID);

        assertEquals(created.id(), recovered.paymentIntentId());
        assertEquals(created.id(), orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getPaymentIntentId());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).createPaymentIntent(
                eq(12_345L), eq(String.valueOf(ORDER_ID)), keyCaptor.capture());
        assertEquals(saved.getIdempotencyKey(), keyCaptor.getAllValues().get(0));
        assertEquals(
                keyCaptor.getAllValues().get(0),
                keyCaptor.getAllValues().get(1)
        );
    }

    private static StripePaymentSnapshot snapshot(String status, long amountReceived) {
        String id = "requires_payment_method".equals(status) ? "pi_test_takeout_501" : INTENT_ID;
        return new StripePaymentSnapshot(id, 12_345L, amountReceived, "cny", status,
                false, String.valueOf(ORDER_ID), null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = {OrderStripePaymentMapper.class, StripePaymentEventMapper.class, OrderMapper.class},
            annotationClass = Mapper.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:stripe-payment-transaction;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
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
        MerchantSseService merchantSseService() {
            return mock(MerchantSseService.class);
        }

        @Bean
        OrderService orderService(OrderMapper orderMapper, MerchantSseService merchantSseService) {
            return new OrderService(null, null, null, orderMapper, null, null, null, null,
                    merchantSseService, new DeliveryFeeCalculator(), null);
        }

        @Bean
        StripePaymentTransactionService transactionService(OrderStripePaymentMapper orderPaymentMapper,
                StripePaymentEventMapper paymentEventMapper, OrderMapper orderMapper, OrderService orderService) {
            return new StripePaymentTransactionService(orderPaymentMapper, paymentEventMapper, orderMapper, orderService);
        }

        @Bean
        StripePaymentGateway stripePaymentGateway() {
            return mock(StripePaymentGateway.class);
        }

        @Bean
        StripePaymentProperties stripePaymentProperties() {
            return new StripePaymentProperties();
        }

        @Bean
        StripePaymentService stripePaymentService(StripePaymentTransactionService transactions,
                                                  StripePaymentGateway gateway, StripePaymentProperties properties) {
            return new StripePaymentService(transactions, gateway, properties);
        }
    }
}
