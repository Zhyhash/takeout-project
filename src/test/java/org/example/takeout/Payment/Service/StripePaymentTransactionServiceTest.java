package org.example.takeout.Payment.Service;

import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.example.takeout.Order.Domain.DeliveryFeeCalculator;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Payment.Config.StripePaymentProperties;
import org.example.takeout.Payment.Config.StripePaymentRecoveryTask;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentEvent;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.StripePaymentEventMapper;
import org.example.takeout.Payment.VO.StripePaymentView;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.annotation.MapperScan;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.concurrent.*;

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
    @Autowired private OrderCommandService orderCommands;
    @Autowired private StripePaymentRecoveryTask recoveryTask;

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
                    payment_started_at TIMESTAMP,
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
                    update_time TIMESTAMP NOT NULL,
                    reconciliation_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
        jdbc.execute("DROP TABLE IF EXISTS order_item");
        jdbc.execute("DROP TABLE IF EXISTS product");
        jdbc.execute("CREATE TABLE product (id BIGINT PRIMARY KEY, stock INT, stock_version INT, version INT, status INT, is_deleted INT)");
        jdbc.execute("CREATE TABLE order_item (id BIGINT PRIMARY KEY, order_id BIGINT, product_id BIGINT, product_name VARCHAR(100), product_price DECIMAL(10,2), quantity INT, subtotal DECIMAL(10,2), stock_version INT, product_picture VARCHAR(100))");
        jdbc.update("INSERT INTO product VALUES (901, 7, 0, 0, 1, 0)");
        jdbc.update("INSERT INTO order_item VALUES (1, ?, 901, 'item', 1.00, 3, 3.00, 0, NULL)", ORDER_ID);
        LocalDateTime created = LocalDateTime.now().minusMinutes(1);
        jdbc.update("""
                INSERT INTO orders (
                    id, order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                    total_amount, status, receiver_name, receiver_phone, receiver_address,
                    create_time, timeout_cancel_available_at, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ORDER_ID, "ORD-501", USER_ID, "request-501", "fingerprint", MERCHANT_ID,
                "Test shop", 123.45, OrderStatusEnum.PAYING.getCode(), "Test user", "13000000000",
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
        assertEquals(OrderStatusEnum.PAYING.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
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

        assertThrows(BusinessException.class, () -> transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_late_success", "payment_intent.succeeded", false, snapshot("succeeded", 12_345L))));
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_late_success"));

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertNull(orderMapper.selectById(ORDER_ID).getPayTime());
        assertEquals("requires_payment_method", orderPaymentMapper.selectPaymentByOrderId(ORDER_ID).getStripeStatus());
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void payingOrderCanBeConfirmedAfterTheOriginalDeadline() {
        jdbc.update("UPDATE orders SET create_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(31), ORDER_ID);
        transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_expired_success", "payment_intent.succeeded", false, snapshot("succeeded", 12_345L)));
        assertEquals(OrderStatusEnum.PAID.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        assertNotNull(orderMapper.selectById(ORDER_ID).getPayTime());
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
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
        jdbc.update("UPDATE orders SET status = 0 WHERE id = ?", ORDER_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OrderStripePayment> first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return transactions.beginOrderPayment(ORDER_ID, USER_ID);
            });
            Future<OrderStripePayment> second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return transactions.beginOrderPayment(ORDER_ID, USER_ID);
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

        assertEquals(OrderStatusEnum.PAYING.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void ambientTransactionIsRejectedBeforeAnyStripeRequest() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        payments.createOrReuseOrderPaymentIntent(ORDER_ID)));
        verifyNoInteractions(gateway);
    }

    @Test
    void paymentAndEventReceiptRollBackTogetherAndCanBeRetried() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                            "evt_retry", "payment_intent.succeeded", false, snapshot("succeeded", 12_345L)));
                    throw new IllegalStateException("force rollback");
                }));

        assertEquals(OrderStatusEnum.PAYING.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
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

        assertEquals(OrderStatusEnum.PAYING.getCode(), orderMapper.selectById(ORDER_ID).getStatus());
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
        jdbc.update("UPDATE orders SET status = 0 WHERE id = ?", ORDER_ID);
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

        jdbc.update("UPDATE order_stripe_payments SET next_retry_at = CURRENT_TIMESTAMP WHERE order_id = ?", ORDER_ID);
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



    @Test
    void normalCreationCommitsEligibilityAndKeyBeforeHttpThenWebhookConfirms() {
        resetToWaitingWithoutPayment();
        when(gateway.createPaymentIntent(eq(12_345L), eq("501"), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            // Independent connection proves preparation has COMMITTED, not just flushed.
            JdbcTemplate independent = new JdbcTemplate(((DataSourceTransactionManager) transactionManager).getDataSource());
            assertEquals(1, independent.queryForObject("SELECT status FROM orders WHERE id = 501", Integer.class));
            assertNotNull(independent.queryForObject("SELECT payment_started_at FROM orders WHERE id = 501", LocalDateTime.class));
            assertNull(independent.queryForObject("SELECT pay_time FROM orders WHERE id = 501", LocalDateTime.class));
            assertEquals(call.getArgument(2), independent.queryForObject(
                    "SELECT idempotency_key FROM order_stripe_payments WHERE order_id = 501", String.class));
            assertEquals(7, stock());
            return new StripePaymentSnapshot(INTENT_ID, 12_345L, 0L, "cny", "requires_payment_method", false, "501", "secret");
        });
        assertEquals("secret", payments.createOrReuseOrderPaymentIntent(ORDER_ID).clientSecret());
        assertEquals(1, status());
        transactions.recordWebhookPaymentEvent(successEvent("evt_normal"));
        assertEquals(2, status());
        assertEquals("COMPLETE", payment().getReconciliationState());
        assertEquals(7, stock());
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
    }

    @Test
    void firstBindingAlsoAppliesImmediateSuccess() {
        resetToWaitingWithoutPayment();
        when(gateway.createPaymentIntent(eq(12_345L), eq("501"), anyString()))
                .thenReturn(snapshot("succeeded", 12_345L));
        StripePaymentView result = payments.createOrReuseOrderPaymentIntent(ORDER_ID);
        assertEquals(2, result.orderStatus());
        assertNull(result.clientSecret());
        assertEquals("succeeded", payment().getStripeStatus());
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
    }

    @Test
    void concurrentFullPaymentRequestsUseOneDurableLeaseAndOneRemoteCreate() throws Exception {
        resetToWaitingWithoutPayment();
        CountDownLatch enteredHttp = new CountDownLatch(1);
        CountDownLatch finishHttp = new CountDownLatch(1);
        when(gateway.createPaymentIntent(eq(12_345L), eq("501"), anyString())).thenAnswer(call -> {
            enteredHttp.countDown();
            assertTrue(finishHttp.await(5, TimeUnit.SECONDS));
            return snapshot("requires_payment_method", 0L);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StripePaymentView> first = executor.submit(() -> {
                UserContextHolder.setUserId(USER_ID);
                try { return payments.createOrReuseOrderPaymentIntent(ORDER_ID); }
                finally { UserContextHolder.clear(); }
            });
            assertTrue(enteredHttp.await(5, TimeUnit.SECONDS));
            StripePaymentView second = payments.createOrReuseOrderPaymentIntent(ORDER_ID);
            assertEquals(1, second.orderStatus());
            assertNull(second.clientSecret());
            finishHttp.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS).paymentIntentId());
        } finally { finishHttp.countDown(); executor.shutdownNow(); }
        verify(gateway, times(1)).createPaymentIntent(eq(12_345L), eq("501"), anyString());
        assertEquals(1, payment().getRetryCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_stripe_payments", Integer.class));
    }

    @Test
    void paymentWinsEligibilityCasAndTimeoutCannotRestoreStockWhileHttpIsInFlight() {
        resetToWaitingWithoutPayment();
        when(gateway.createPaymentIntent(eq(12_345L), eq("501"), anyString())).thenAnswer(call -> {
            jdbc.update("UPDATE orders SET create_time = ? WHERE id = 501", LocalDateTime.now().minusMinutes(31));
            assertFalse(orderService.cancelTimeoutOrder(ORDER_ID, LocalDateTime.now().minusMinutes(30)));
            assertEquals(1, status());
            assertEquals(7, stock());
            return snapshot("succeeded", 12_345L);
        });
        payments.createOrReuseOrderPaymentIntent(ORDER_ID);
        assertEquals(2, status());
        assertEquals(7, stock());
    }

    @Test
    void timeoutWinsCasAndPaymentCannotCreateAnIntentOrRestoreStockTwice() {
        resetToWaitingWithoutPayment();
        jdbc.update("UPDATE orders SET create_time = ? WHERE id = 501", LocalDateTime.now().minusMinutes(31));
        assertTrue(orderService.cancelTimeoutOrder(ORDER_ID, LocalDateTime.now().minusMinutes(30)));
        assertThrows(BusinessException.class, () -> payments.createOrReuseOrderPaymentIntent(ORDER_ID));
        assertFalse(orderService.cancelTimeoutOrder(ORDER_ID, LocalDateTime.now().minusMinutes(30)));
        orderService.cancelOrder(ORDER_ID);
        assertEquals(4, status());
        assertEquals(10, stock());
        assertNull(payment());
        verifyNoInteractions(gateway);
    }

    @Test
    void userAndMerchantCannotBypassPayingCancellationRules() {
        assertThrows(BusinessException.class, () -> orderService.cancelOrder(ORDER_ID));
        assertThrows(BusinessException.class, () -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));
        assertEquals(1, status());
        assertEquals(7, stock());
    }

    @Test
    void lostCreationResponseIsRecoveredWithTheSameKeyAndBackoff() {
        resetToWaitingWithoutPayment();
        when(gateway.createPaymentIntent(eq(12_345L), eq("501"), anyString()))
                .thenThrow(new org.example.takeout.Payment.Gateway.StripeGatewayException(
                        org.example.takeout.Payment.Gateway.StripeGatewayException.Kind.OUTCOME_UNKNOWN))
                .thenReturn(snapshot("requires_payment_method", 0L));
        assertThrows(RuntimeException.class, () -> payments.createOrReuseOrderPaymentIntent(ORDER_ID));
        String key = payment().getIdempotencyKey();
        assertEquals(1, status());
        assertNull(payment().getPaymentIntentId());
        // 占位即推后 next_retry_at，作为"本次已尝试"的标记。
        assertNotNull(payment().getNextRetryAt());
        recoveryTask.recoverPendingPayments(); // Not due: no high frequency retry.
        assertEquals(1, payment().getRetryCount());
        makeRecoveryDue();
        recoveryTask.recoverPendingPayments();
        assertEquals(INTENT_ID, payment().getPaymentIntentId());
        assertEquals(key, payment().getIdempotencyKey());
        verify(gateway, times(2)).createPaymentIntent(12_345L, "501", key);
    }

    @Test
    void existingIntentRecoveryUsesRetrieveEvenAfterIdempotencyExpires() {
        jdbc.update("UPDATE order_stripe_payments SET create_time = ?", LocalDateTime.now().minusDays(2));
        when(gateway.retrievePaymentIntent(INTENT_ID)).thenReturn(snapshot("succeeded", 12_345L));
        recoveryTask.recoverPendingPayments();
        assertEquals(2, status());
        assertEquals("COMPLETE", payment().getReconciliationState());
        verify(gateway).retrievePaymentIntent(INTENT_ID);
        verify(gateway, never()).createPaymentIntent(anyLong(), anyString(), anyString());
    }

    @Test
    void unknownCreationOutsideIdempotencyWindowRequiresReviewWithoutAnotherCreate() {
        // 幂等有效期由 create_time + 23h 推导，两天前的记录已超出窗口。
        jdbc.update("UPDATE order_stripe_payments SET payment_intent_id = NULL, create_time = ?",
                LocalDateTime.now().minusDays(2));
        recoveryTask.recoverPendingPayments();
        assertEquals("REVIEW", payment().getReconciliationState());
        assertNull(payment().getNextRetryAt());
        assertEquals(1, status());
        assertEquals(7, stock());
        recoveryTask.recoverPendingPayments();
        verifyNoInteractions(gateway);
    }

    @Test
    void abandonedPaymentIsCanceledAtStripeBeforeInventoryIsReturnedOnce() {
        jdbc.update("UPDATE orders SET create_time = ? WHERE id = 501", LocalDateTime.now().minusMinutes(31));
        when(gateway.retrievePaymentIntent(INTENT_ID)).thenReturn(snapshot("requires_action", 0L));
        when(gateway.cancelPaymentIntent(INTENT_ID, "cancel-test-attempt-501")).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, status());
            assertEquals(7, stock());
            return snapshot("canceled", 0L);
        });
        recoveryTask.recoverPendingPayments();
        assertEquals(4, status());
        assertEquals(3, orderMapper.selectById(ORDER_ID).getCancelSource());
        assertEquals(10, stock());
        transactions.recordWebhookPaymentEvent(new StripePaymentEvent("evt_cancel", "payment_intent.canceled", false, snapshot("canceled", 0L)));
        transactions.syncPaymentStatusFromSnapshot(snapshot("requires_action", 0L));
        assertEquals("canceled", payment().getStripeStatus());
        assertEquals(10, stock());
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void cancellationFailureOrUnknownProcessingStateNeverReturnsStockBasedOnTimeAlone() {
        jdbc.update("UPDATE orders SET create_time = ? WHERE id = 501", LocalDateTime.now().minusMinutes(31));
        when(gateway.retrievePaymentIntent(INTENT_ID)).thenReturn(snapshot("processing", 0L));
        when(gateway.cancelPaymentIntent(anyString(), anyString())).thenThrow(new IllegalStateException("unavailable"));
        recoveryTask.recoverPendingPayments();
        assertEquals(1, status());
        assertEquals("processing", payment().getStripeStatus());
        assertEquals("PENDING", payment().getReconciliationState());
        assertEquals(7, stock());
        assertTrue(payment().getNextRetryAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void successRacingCancellationAtStripeIsConfirmedOnTheNextRetrieve() {
        jdbc.update("UPDATE orders SET create_time = ? WHERE id = 501", LocalDateTime.now().minusMinutes(31));
        when(gateway.retrievePaymentIntent(INTENT_ID)).thenReturn(snapshot("processing", 0L), snapshot("succeeded", 12_345L));
        when(gateway.cancelPaymentIntent(anyString(), anyString())).thenThrow(new IllegalStateException("already succeeded"));
        recoveryTask.recoverPendingPayments();
        assertEquals(1, status());
        makeRecoveryDue();
        recoveryTask.recoverPendingPayments();
        assertEquals(2, status());
        assertEquals(7, stock());
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
    }

    @Test
    void confirmationDatabaseFailureRollsBackReceiptAndPaymentThenSameWebhookCanRetry() {
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT reject_stripe_paid CHECK (status <> 2)");
        assertThrows(RuntimeException.class, () -> transactions.recordWebhookPaymentEvent(successEvent("evt_db_retry")));
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_db_retry"));
        assertEquals("requires_payment_method", payment().getStripeStatus());
        assertEquals(1, status());
        verifyNoInteractions(merchantSseService);
        jdbc.execute("ALTER TABLE orders DROP CONSTRAINT reject_stripe_paid");
        transactions.recordWebhookPaymentEvent(successEvent("evt_db_retry"));
        assertEquals(2, status());
        assertEquals(1, paymentEventMapper.countEventsByEventId("evt_db_retry"));
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
    }

    @Test
    void cancellationInventoryFailureRollsBackOrderPaymentAndReceipt() {
        jdbc.execute("ALTER TABLE product ADD CONSTRAINT reject_stock_return CHECK (stock < 10)");
        StripePaymentEvent canceled = new StripePaymentEvent("evt_stock_retry", "payment_intent.canceled", false, snapshot("canceled", 0L));
        assertThrows(RuntimeException.class, () -> transactions.recordWebhookPaymentEvent(canceled));
        assertEquals(1, status());
        assertEquals(7, stock());
        assertEquals("requires_payment_method", payment().getStripeStatus());
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_stock_retry"));
        jdbc.execute("ALTER TABLE product DROP CONSTRAINT reject_stock_return");
        transactions.recordWebhookPaymentEvent(canceled);
        assertEquals(4, status());
        assertEquals(10, stock());
    }

    @Test
    void merchantRefundPlaceholderCannotCancelAStripePaidOrderOrRestoreStock() {
        transactions.syncPaymentStatusFromSnapshot(snapshot("succeeded", 12_345L));
        assertThrows(BusinessException.class, () -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));
        assertEquals(2, status());
        assertEquals("succeeded", payment().getStripeStatus());
        assertEquals(7, stock());
    }

    @Test
    void claimReservesTheAttemptOnlyUntilItsBackoffWindowEnds() {
        OrderStripePayment first = transactions.claimPaymentAttempt(ORDER_ID);
        assertNotNull(first);
        assertEquals(1, first.getRetryCount());
        // 占位把 next_retry_at 推到未来，同一时刻的第二次领取必然失败。
        assertNull(transactions.claimPaymentAttempt(ORDER_ID));

        // 退避到期后可以被重新领取，retryCount 继续累加。
        jdbc.update("UPDATE order_stripe_payments SET next_retry_at = ?", LocalDateTime.now().minusSeconds(1));
        OrderStripePayment second = transactions.claimPaymentAttempt(ORDER_ID);
        assertNotNull(second);
        assertEquals(2, second.getRetryCount());
        assertTrue(payment().getNextRetryAt().isAfter(LocalDateTime.now()));
        assertEquals(1, status());
        verifyNoInteractions(gateway);
    }

    @Test
    void retryBudgetProducesAnExplicitReviewState() {
        jdbc.update("UPDATE order_stripe_payments SET retry_count = 96");
        recoveryTask.recoverPendingPayments();
        assertEquals("REVIEW", payment().getReconciliationState());
        assertEquals(1, status());
        verifyNoInteractions(gateway);
    }

    @Test
    void unboundWebhookIsNotAcknowledgedAsProcessedAndCanRetryAfterRecoveryBinding() {
        jdbc.update("UPDATE order_stripe_payments SET payment_intent_id = NULL");
        assertThrows(BusinessException.class, () -> transactions.recordWebhookPaymentEvent(successEvent("evt_unbound")));
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_unbound"));
        when(gateway.createPaymentIntent(12_345L, "501", "test-attempt-501")).thenReturn(snapshot("succeeded", 12_345L));
        recoveryTask.recoverPendingPayments();
        transactions.recordWebhookPaymentEvent(successEvent("evt_unbound"));
        assertEquals(2, status());
        verify(merchantSseService, times(1)).notifyNewOrder(MERCHANT_ID);
    }

    @Test
    void stockVersionResetIsPreservedDuringStripeCancellation() {
        jdbc.update("UPDATE product SET stock = 100, stock_version = 1");
        transactions.syncPaymentStatusFromSnapshot(snapshot("canceled", 0L));
        assertEquals(4, status());
        assertEquals(100, stock());
    }

    @Test
    void preparationInsertFailureRollsBackEligibilityAndNeverCallsStripe() {
        resetToWaitingWithoutPayment();
        jdbc.execute("ALTER TABLE order_stripe_payments ADD CONSTRAINT reject_payment_insert CHECK (order_id <> 501)");
        assertThrows(RuntimeException.class, () -> payments.createOrReuseOrderPaymentIntent(ORDER_ID));
        assertEquals(0, status());
        assertNull(payment());
        assertNull(jdbc.queryForObject("SELECT payment_started_at FROM orders WHERE id = 501", LocalDateTime.class));
        assertNull(jdbc.queryForObject("SELECT pay_time FROM orders WHERE id = 501", LocalDateTime.class));
        assertEquals(7, stock());
        verifyNoInteractions(gateway);
    }

    @Test
    void notificationIsAfterOutermostCommitAndNeverRunsForRolledBackStripeResult() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            transactions.recordWebhookPaymentEvent(successEvent("evt_outer_rollback"));
            assertEquals(2, status());
            verifyNoInteractions(merchantSseService);
            tx.setRollbackOnly();
        });
        assertEquals(1, status());
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_outer_rollback"));
        assertEquals("requires_payment_method", payment().getStripeStatus());
        doAnswer(call -> {
            assertEquals(2, status());
            assertNotNull(orderMapper.selectById(ORDER_ID).getPayTime());
            return null;
        }).when(merchantSseService).notifyNewOrder(MERCHANT_ID);
        transactions.recordWebhookPaymentEvent(successEvent("evt_outer_rollback"));
        verify(merchantSseService).notifyNewOrder(MERCHANT_ID);
    }

    @Test
    void malformedOrUnknownStripeStatusRemainsPendingAndIsNotAcknowledged() {
        StripePaymentSnapshot unknown = snapshot("unknown_future_status", 0L);
        assertThrows(BusinessException.class, () -> transactions.recordWebhookPaymentEvent(
                new StripePaymentEvent("evt_unknown", "payment_intent.payment_failed", false, unknown)));
        assertEquals(1, status());
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_unknown"));
        assertEquals("PENDING", payment().getReconciliationState());
        assertEquals(7, stock());
    }

    @Test
    void canceledSnapshotWithReceivedMoneyCannotPretendARefundHappened() {
        assertThrows(BusinessException.class, () -> transactions.syncPaymentStatusFromSnapshot(snapshot("canceled", 1000L)));
        assertEquals(1, status());
        assertEquals("PENDING", payment().getReconciliationState());
        assertEquals(7, stock());
    }

    @Test
    void unrelatedSignedStripePaymentDoesNotGetAssociatedWithAnOrder() {
        StripePaymentSnapshot unrelated = new StripePaymentSnapshot("pi_unrelated", 1000L, 1000L, "cny", "succeeded",
                false, null, null);
        assertNull(transactions.recordWebhookPaymentEvent(new StripePaymentEvent(
                "evt_unrelated", "payment_intent.succeeded", false, unrelated)));
        assertEquals(0, paymentEventMapper.countEventsByEventId("evt_unrelated"));
        assertEquals(1, status());
        assertEquals(INTENT_ID, payment().getPaymentIntentId());
    }

    @Test
    void creationGuardRejectsBoundCompletedOrExpiredAttemptsBeforeHttp() {
        jdbc.update("UPDATE order_stripe_payments SET payment_intent_id = NULL");
        OrderStripePayment attempt = transactions.claimPaymentAttempt(ORDER_ID);
        assertNotNull(attempt);
        assertTrue(transactions.canCreatePaymentIntent(attempt));

        // 已被绑定 → 不允许再创建第二笔支付意图
        jdbc.update("UPDATE order_stripe_payments SET payment_intent_id = ?", INTENT_ID);
        assertFalse(transactions.canCreatePaymentIntent(attempt));

        // 仍未绑定，但已离开待处理状态 → 不允许再创建
        jdbc.update("UPDATE order_stripe_payments SET payment_intent_id = NULL, reconciliation_state = 'COMPLETE'");
        assertFalse(transactions.canCreatePaymentIntent(attempt));

        // 回到待处理，但幂等窗口已过期 → 不允许再创建
        jdbc.update("UPDATE order_stripe_payments SET reconciliation_state = 'PENDING', create_time = ?",
                LocalDateTime.now().minusDays(2));
        assertFalse(transactions.canCreatePaymentIntent(attempt));

        assertEquals(1, status());
        verifyNoInteractions(gateway);
    }

    @Test
    void conflictingTerminalWebhookKeepsTheFirstTerminalStateAndFlagsReview() {
        transactions.syncPaymentStatusFromSnapshot(snapshot("canceled", 0L));
        when(gateway.verifySignatureAndParsePaymentEvent("signed", "signature")).thenReturn(successEvent("evt_conflict"));

        // 终态冲突重投也不会改变结论，因此不再抛异常让 Stripe 连续重投。
        payments.verifyAndProcessWebhookEvent("signed", "signature");

        assertEquals(4, status());
        assertEquals(10, stock());
        // 保留先到的终态，不被冲突事件改写。
        assertEquals("canceled", payment().getStripeStatus());
        assertEquals("REVIEW", payment().getReconciliationState());
        // 已收到并转人工，记下回执让 Stripe 停止重投。
        assertEquals(1, paymentEventMapper.countEventsByEventId("evt_conflict"));
        verifyNoInteractions(merchantSseService);
    }

    @Test
    void lateCancellationForAnAlreadyPaidOrderIsFlaggedInsteadOfSilentlyIgnored() {
        transactions.syncPaymentStatusFromSnapshot(snapshot("succeeded", 12_345L));
        assertEquals(2, status());

        transactions.syncPaymentStatusFromSnapshot(snapshot("canceled", 0L));

        // 已支付订单不会被降级取消，但冲突必须留下痕迹，不能静默吞掉。
        assertEquals(2, status());
        assertEquals(7, stock());
        assertEquals("succeeded", payment().getStripeStatus());
        assertEquals("REVIEW", payment().getReconciliationState());
    }

    private void resetToWaitingWithoutPayment() {
        jdbc.update("DELETE FROM order_stripe_payments WHERE order_id = ?", ORDER_ID);
        jdbc.update("UPDATE orders SET status = 0, payment_started_at = NULL WHERE id = ?", ORDER_ID);
    }

    private void makeRecoveryDue() {
        jdbc.update("UPDATE order_stripe_payments SET next_retry_at = ?", LocalDateTime.now().minusSeconds(1));
    }

    private int status() { return jdbc.queryForObject("SELECT status FROM orders WHERE id = 501", Integer.class); }
    private int stock() { return jdbc.queryForObject("SELECT stock FROM product WHERE id = 901", Integer.class); }
    private OrderStripePayment payment() { return orderPaymentMapper.selectPaymentByOrderId(ORDER_ID); }
    private static StripePaymentEvent successEvent(String id) {
        return new StripePaymentEvent(id, "payment_intent.succeeded", false, snapshot("succeeded", 12_345L));
    }

    private static StripePaymentSnapshot snapshot(String status, long amountReceived) {
        String id = "requires_payment_method".equals(status) ? "pi_test_takeout_501" : INTENT_ID;
        return new StripePaymentSnapshot(id, 12_345L, amountReceived, "cny", status,
                false, String.valueOf(ORDER_ID), null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = {OrderStripePaymentMapper.class, StripePaymentEventMapper.class, OrderMapper.class, ProductMapper.class},
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
        OrderService orderService(OrderMapper orderMapper, MerchantSseService merchantSseService,
                                  OrderItemService items, OrderItemMapper itemMapper) {
            return new OrderService(null, items, itemMapper, orderMapper, null, null, null, null,
                    merchantSseService, new DeliveryFeeCalculator(), null);
        }

        @Bean
        OrderItemService orderItems(ProductMapper productMapper, OrderItemMapper itemMapper) {
            return new OrderItemService(new ProductCommandService(productMapper, null), itemMapper, null);
        }

        @Bean
        OrderCommandService orderCommands(OrderMapper mapper, OrderItemService items) {
            return new OrderCommandService(mapper, items);
        }

        @Bean
        StripePaymentRecoveryTask recoveryTask(StripePaymentTransactionService tx, StripePaymentService service) {
            return new StripePaymentRecoveryTask(tx, service);
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
