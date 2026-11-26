package org.example.takeout.Payment.Service;

import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Payment.Config.StripePaymentRecoveryTask;
import org.example.takeout.Payment.Controller.StripeWebhookController;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Gateway.StripeWebhookEvent;
import org.example.takeout.Payment.Gateway.StripeWebhookGateway;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.testsupport.ConcurrentTestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "takeout.lab.enabled", matches = "true")
@SpringJUnitConfig(StripePaymentTransactionServiceTest.TestConfig.class)
@TestPropertySource(properties = "payment.stripe.enabled=true")
class PhaseOnePaymentMysqlTest {
    private static final long USER = 721001L;
    private static final long MERCHANT = 721002L;
    private long orderId;
    private String intent;
    private Long fixtureProductId;

    @TestBean(methodName = "labDataSource")
    private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StripePaymentService payments;
    @Autowired private OrderStripePaymentMapper records;
    @Autowired private StripePaymentGateway gateway;
    @Autowired private StripeWebhookGateway verifier;
    @Autowired private StripeWebhookService webhook;
    @Autowired private StripePaymentRecoveryTask recovery;
    @Autowired private MerchantSseService notifications;
    @Autowired private OrderCommandService orderCommands;
    @Autowired private StripeRefundGateway refunds;
    @Autowired private PlatformTransactionManager txManager;

    static DataSource labDataSource() {
        int port = Integer.parseInt(System.getProperty("takeout.test.mysql.port"));
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/takeout_integration_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "root");
    }

    @BeforeEach
    void insertWaitingOrder() {
        reset(gateway, verifier, notifications, refunds);
        UserContextHolder.setUserId(USER);
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO orders (order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                    total_amount, original_amount, status, receiver_name, receiver_phone, receiver_address)
                VALUES (?, ?, ?, ?, ?, 'Shop', 12.34, 12.34, ?, 'Test', '13000000000', 'Lab')
                """, token, USER, token, token, MERCHANT, OrderStatusEnum.WAIT_PAY.getCode());
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, token);
        intent = "pi_lab_" + token;
    }

    @AfterEach
    void releaseFixture() {
        UserContextHolder.clear();
        if (intent == null) {
            return;
        }
        jdbc.update("DELETE FROM stripe_payment_events WHERE payment_intent_id=?", intent);
        jdbc.update("DELETE FROM order_stripe_refund_attempts WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM order_stripe_refunds WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM order_stripe_payments WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM order_item WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM orders WHERE id=?", orderId);
        if (fixtureProductId != null) {
            jdbc.update("DELETE FROM product WHERE id=?", fixtureProductId);
            jdbc.update("DELETE FROM category WHERE id=?", fixtureProductId);
        }
    }

    @Test
    void recoveryBindsOneRemoteIntentAfterDatabaseRejectsFirstBinding() {
        var remote = new HashMap<String, StripePaymentSnapshot>();
        when(gateway.createPaymentIntent(eq(1234L), eq(Long.toString(orderId)), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String key = call.getArgument(2);
            assertEquals(key, records.selectPaymentByOrderId(orderId).getIdempotencyKey());
            return remote.computeIfAbsent(key, ignored -> snapshot("succeeded", 1234));
        });
        // DDL outside any application transaction. The value is generated locally for this test.
        jdbc.execute("ALTER TABLE order_stripe_payments ADD CONSTRAINT lab_reject_bind CHECK (payment_intent_id <> '" + intent + "')");
        try {
            DataAccessException failure = assertThrows(DataAccessException.class,
                    () -> payments.createOrReuseOrderPaymentIntent(orderId));
            SQLException sql = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
            assertEquals(3819, sql.getErrorCode(), "Injected MySQL CHECK must be the reason for failure");
            assertTrue(sql.getMessage().contains("lab_reject_bind"));
            var pending = records.selectPaymentByOrderId(orderId);
            assertNull(pending.getPaymentIntentId());
            assertEquals("PENDING", pending.getReconciliationState());
            assertEquals(OrderStatusEnum.PAYING.getCode(), orderStatus());
            assertNull(jdbc.queryForObject("SELECT pay_time FROM orders WHERE id=?", String.class, orderId));
            verifyNoInteractions(notifications);
            System.out.printf("LAB bind rollback mysql=%d remoteObjects=%d order=PAYING localIntent=null%n",
                    sql.getErrorCode(), remote.size());
        } finally {
            jdbc.execute("ALTER TABLE order_stripe_payments DROP CHECK lab_reject_bind");
        }
        jdbc.update("UPDATE order_stripe_payments SET next_retry_at=NOW() WHERE order_id=?", orderId);
        recovery.recoverPendingPayments();
        var paid = records.selectPaymentByOrderId(orderId);
        verify(gateway, times(2)).createPaymentIntent(1234L, Long.toString(orderId), paid.getIdempotencyKey());
        assertEquals(1, remote.size());
        assertEquals(intent, paid.getPaymentIntentId());
        assertEquals("RECONCILED", paid.getReconciliationState());
        assertEquals(OrderStatusEnum.PAID.getCode(), orderStatus());
        verify(notifications, times(1)).notifyNewOrder(MERCHANT);
        System.out.println("LAB bind recovery calls=2 keys=1 remoteObjects=1 order=PAID notifications=1");
    }

    @Test
    void webhookAndRetrieveCommitOnePaidTransition() {
        when(gateway.createPaymentIntent(eq(1234L), eq(Long.toString(orderId)), anyString()))
                .thenReturn(snapshot("requires_payment_method", 0));
        assertEquals(intent, payments.createOrReuseOrderPaymentIntent(orderId).paymentIntentId());
        jdbc.update("UPDATE order_stripe_payments SET next_retry_at=NOW() WHERE order_id=?", orderId);
        var ready = new CyclicBarrier(2);
        var success = snapshot("succeeded", 1234);
        var eventId = "evt_lab_" + orderId;
        var event = new StripeWebhookEvent(eventId, "payment_intent.succeeded", false, success, null);
        when(verifier.verifySignatureAndParseEvent("event", "fake")).thenAnswer(call -> {
            ready.await(8, TimeUnit.SECONDS);
            return event;
        });
        when(gateway.retrievePaymentIntent(intent)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ready.await(8, TimeUnit.SECONDS);
            return success;
        });
        ConcurrentTestTemplate.runTwoTasks(Duration.ofSeconds(20),
                () -> { webhook.processWebhookEvent("event", "fake"); return null; },
                () -> {
                    UserContextHolder.setUserId(USER);
                    try { return payments.syncOrderPaymentStatusFromStripe(orderId); }
                    finally { UserContextHolder.clear(); }
                });
        assertEquals(OrderStatusEnum.PAID.getCode(), orderStatus());
        var paid = records.selectPaymentByOrderId(orderId);
        assertEquals("RECONCILED", paid.getReconciliationState());
        assertEquals("succeeded", paid.getStripeStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stripe_payment_events WHERE event_id=?",
                Integer.class, eventId));
        verify(gateway).retrievePaymentIntent(intent);
        verify(notifications, times(1)).notifyNewOrder(MERCHANT);
        System.out.println("LAB concurrent webhook/retrieve order=PAID receipts=1 notifications=1");
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id=?", Integer.class, orderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "succeeded"})
    void lateFirstPaymentReceiptIsAcceptedAfterMerchantRefund(String refundSnapshotStatus) {
        long productId = 1_000_000L + orderId;
        fixtureProductId = productId;
        jdbc.update("INSERT INTO category (id, merchant_id, category_name) VALUES (?, ?, ?)",
                productId, MERCHANT, "lab-" + productId);
        jdbc.update("""
                INSERT INTO product (id, category_id, product_name, price, stock, stock_version, merchant_id, status)
                VALUES (?, ?, ?, 12.34, 7, 0, ?, 0)
                """, productId, productId, "lab-" + productId, MERCHANT);
        jdbc.update("""
                INSERT INTO order_item (order_id, product_id, product_name, product_price, quantity, subtotal, stock_version)
                VALUES (?, ?, 'Lab', 12.34, 1, 12.34, 0)
                """, orderId, productId);
        var paid = snapshot("succeeded", 1234);
        when(gateway.createPaymentIntent(eq(1234L), eq(Long.toString(orderId)), anyString())).thenReturn(paid);
        assertEquals(OrderStatusEnum.PAID.getCode(), payments.createOrReuseOrderPaymentIntent(orderId).orderStatus());
        when(refunds.createRefund(eq(intent), eq(1234L), anyString())).thenReturn(
                new StripeRefundSnapshot("re_" + orderId, intent, 1234L, "cny", refundSnapshotStatus, null));
        orderCommands.rejectOrderByMerchant(orderId, MERCHANT);
        int afterRefund = "pending".equals(refundSnapshotStatus)
                ? OrderStatusEnum.REFUNDING.getCode() : OrderStatusEnum.REFUNDED.getCode();
        assertEquals(afterRefund, orderStatus());
        var eventId = "evt_late_" + orderId;
        when(verifier.verifySignatureAndParseEvent("delayed", "fake")).thenReturn(
                new StripeWebhookEvent(eventId, "payment_intent.succeeded", false, paid, null));
        var controller = new StripeWebhookController(webhook);
        int first = controller.receiveStripeWebhookEvent("delayed", "fake").getStatusCode().value();
        int replay = controller.receiveStripeWebhookEvent("delayed", "fake").getStatusCode().value();
        int receipts = jdbc.queryForObject("SELECT COUNT(*) FROM stripe_payment_events WHERE event_id=?", Integer.class, eventId);
        assertEquals(afterRefund, orderStatus());
        assertEquals("RECONCILED", records.selectPaymentByOrderId(orderId).getReconciliationState());
        assertEquals(8, jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, productId));
        verify(notifications).notifyNewOrder(MERCHANT);
        verify(refunds).createRefund(eq(intent), eq(1234L), anyString());
        System.out.printf("LAB late success refund=%s orderStatus=%d responses=[%d,%d] receipts=%d stock=8 notifications=1%n",
                refundSnapshotStatus, afterRefund, first, replay, receipts);
        assertAll(() -> assertEquals(200, first), () -> assertEquals(200, replay), () -> assertEquals(1, receipts));
    }

    @RepeatedTest(3)
    void overlappingDeliveriesOfOneEventAreBothAcknowledged() throws Exception {
        when(gateway.createPaymentIntent(eq(1234L), eq(Long.toString(orderId)), anyString()))
                .thenReturn(snapshot("requires_payment_method", 0));
        assertEquals(intent, payments.createOrReuseOrderPaymentIntent(orderId).paymentIntentId());
        String eventId = "evt_duplicate_" + orderId;
        var event = new StripeWebhookEvent(eventId, "payment_intent.succeeded", false,
                snapshot("succeeded", 1234), null);
        when(verifier.verifySignatureAndParseEvent("duplicate", "fake")).thenReturn(event);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<String>> deliveries = new ArrayList<>();
        List<String> acknowledgements = new ArrayList<>();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM orders WHERE id=? FOR UPDATE", Long.class, orderId);
                for (int i = 0; i < 2; i++) {
                    deliveries.add(pool.submit(() -> deliverDuplicate()));
                }
                // The lock holders/waiters are observed on the disposable instance; no timing guess.
                String waiters = """
                        SELECT COUNT(DISTINCT w.requesting_thread_id)
                        FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
                          ON w.requesting_engine_lock_id=l.engine_lock_id AND w.engine=l.engine
                        WHERE l.object_schema=DATABASE() AND l.object_name='orders'
                          AND l.index_name='PRIMARY' AND l.lock_type='RECORD'
                        """;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (jdbc.queryForObject(waiters, Integer.class) < 2) {
                    assertTrue(System.nanoTime() < deadline, "Two handlers must reach the order row lock");
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
            });
            for (var delivery : deliveries) { acknowledgements.add(delivery.get(10, TimeUnit.SECONDS)); }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        String redelivery = deliverDuplicate();
        int receipts = jdbc.queryForObject("SELECT COUNT(*) FROM stripe_payment_events WHERE event_id=?",
                Integer.class, eventId);
        assertEquals(1, receipts);
        assertEquals(OrderStatusEnum.PAID.getCode(), orderStatus());
        assertEquals("RECONCILED", records.selectPaymentByOrderId(orderId).getReconciliationState());
        verify(notifications, times(1)).notifyNewOrder(MERCHANT);
        assertEquals("processed", redelivery);
        System.out.printf("LAB duplicate: waiters=2 first=%s replay=%s order=PAID receipts=%d notifications=1%n",
                acknowledgements, redelivery, receipts);
        assertEquals(List.of("processed", "processed"), acknowledgements,
                "A duplicate delivery of the already recorded event should return normally");
    }

    private String deliverDuplicate() {
        try {
            webhook.processWebhookEvent("duplicate", "fake");
            return "processed";
        } catch (DataAccessException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql) {
                return failure.getClass().getSimpleName() + " SQLState=" + sql.getSQLState() + " errno=" + sql.getErrorCode();
            }
            throw failure;
        }
    }

    private StripePaymentSnapshot snapshot(String status, long received) {
        return new StripePaymentSnapshot(intent, 1234L, received, "cny", status, false,
                Long.toString(orderId), null);
    }
}
