package org.example.takeout.Payment.Service;

import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Payment.Config.StripePaymentProperties;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentEvent;
import org.example.takeout.Payment.Gateway.StripeGatewayException;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.VO.StripePaymentView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StripePaymentServiceTest {
    private static final long ORDER_ID = 501L;
    private static final long USER_ID = 101L;

    private final StripePaymentTransactionService transactions = mock(StripePaymentTransactionService.class);
    private final StripePaymentGateway gateway = mock(StripePaymentGateway.class);
    private final StripePaymentProperties properties = new StripePaymentProperties();
    private final StripePaymentService service = new StripePaymentService(transactions, gateway, properties);

    @BeforeEach
    void setUser() {
        UserContextHolder.setUserId(USER_ID);
        when(transactions.canCreatePaymentIntent(any())).thenReturn(true);
    }

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    @Test
    void createUsesTheStoredOrderTotalAndStableAttemptIdempotencyKey() {
        Order order = payableOrder(new BigDecimal("123.45"));
        OrderStripePayment payment = paymentAttempt(null, null);
        StripePaymentSnapshot snapshot = snapshot("pi_stored_amount", "requires_payment_method", 0L);
        when(transactions.requireOrderOwnedByUser(ORDER_ID, USER_ID)).thenReturn(order);
        when(transactions.beginOrderPayment(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.claimPaymentAttempt(ORDER_ID)).thenReturn(payment);
        when(transactions.findOrderPaymentForUser(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.findOrderForRecovery(ORDER_ID)).thenReturn(order);
        when(gateway.createPaymentIntent(12_345L, String.valueOf(ORDER_ID), "attempt-stable")).thenReturn(snapshot);
        when(transactions.bindPaymentIntentToOrder(ORDER_ID, snapshot))
                .thenReturn(paymentAttempt("pi_stored_amount", "requires_payment_method"));

        StripePaymentView result = service.createOrReuseOrderPaymentIntent(ORDER_ID);

        assertEquals(12_345L, result.amountMinor());
        assertEquals("cny", result.currency());
        verify(transactions).beginOrderPayment(ORDER_ID, USER_ID);
        verify(gateway).createPaymentIntent(12_345L, String.valueOf(ORDER_ID), "attempt-stable");
        verify(transactions).bindPaymentIntentToOrder(ORDER_ID, snapshot);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void createRetrievesAnExistingIntentInsteadOfCreatingAnother() {
        Order order = payableOrder(new BigDecimal("54.32"));
        OrderStripePayment payment = paymentAttempt("pi_existing", "requires_action");
        StripePaymentSnapshot snapshot = snapshot("pi_existing", "requires_action", 0L, 5_432L);
        when(transactions.requireOrderOwnedByUser(ORDER_ID, USER_ID)).thenReturn(order);
        when(transactions.beginOrderPayment(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.claimPaymentAttempt(ORDER_ID)).thenReturn(payment);
        when(transactions.findOrderPaymentForUser(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.findOrderForRecovery(ORDER_ID)).thenReturn(order);
        when(gateway.retrievePaymentIntent("pi_existing")).thenReturn(snapshot);
        when(transactions.syncPaymentStatusFromSnapshot(snapshot)).thenReturn(payment);

        StripePaymentView result = service.createOrReuseOrderPaymentIntent(ORDER_ID);

        assertEquals("pi_existing", result.paymentIntentId());
        assertEquals("requires_action", result.paymentStatus());
        verify(gateway).retrievePaymentIntent("pi_existing");
        verify(gateway, never()).createPaymentIntent(anyLong(), anyString(), anyString());
        verify(transactions).syncPaymentStatusFromSnapshot(snapshot);
    }

    @Test
    void stripeNetworkFailureDoesNotConfirmTheOrder() {
        Order order = payableOrder(new BigDecimal("88.00"));
        OrderStripePayment payment = paymentAttempt(null, null);
        payment.setAmountMinor(8_800L);
        when(transactions.requireOrderOwnedByUser(ORDER_ID, USER_ID)).thenReturn(order);
        when(transactions.beginOrderPayment(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.claimPaymentAttempt(ORDER_ID)).thenReturn(payment);
        when(transactions.findOrderPaymentForUser(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.findOrderForRecovery(ORDER_ID)).thenReturn(order);
        when(gateway.createPaymentIntent(8_800L, String.valueOf(ORDER_ID), "attempt-stable"))
                .thenThrow(new IllegalStateException("stripe unavailable"));

        assertThrows(IllegalStateException.class, () -> service.createOrReuseOrderPaymentIntent(ORDER_ID));

        assertEquals(OrderStatusEnum.PAYING.getCode(), order.getStatus());
        verify(transactions, never()).syncPaymentStatusFromSnapshot(any());
        verify(transactions, never()).recordWebhookPaymentEvent(any());
        verify(transactions, never()).bindPaymentIntentToOrder(anyLong(), any());
    }

    @Test
    void invalidFenPrecisionIsRejectedBeforeCreatingAnyStripeObject() {
        Order order = payableOrder(new BigDecimal("12.345"));
        when(transactions.requireOrderOwnedByUser(ORDER_ID, USER_ID)).thenReturn(order);

        assertThrows(RuntimeException.class, () -> service.createOrReuseOrderPaymentIntent(ORDER_ID));

        verify(transactions, never()).beginOrderPayment(anyLong(), anyLong());
        verifyNoInteractions(gateway);
    }

    @Test
    void cancellationDuringIntentCreationDoesNotReturnAUsableClientSecret() {
        Order order = payableOrder(new BigDecimal("123.45"));
        OrderStripePayment payment = paymentAttempt(null, null);
        StripePaymentSnapshot snapshot = snapshot("pi_cancel_race", "requires_payment_method", 0L);
        when(transactions.requireOrderOwnedByUser(ORDER_ID, USER_ID)).thenReturn(order);
        when(transactions.beginOrderPayment(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.claimPaymentAttempt(ORDER_ID)).thenReturn(payment);
        when(transactions.findOrderPaymentForUser(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.findOrderForRecovery(ORDER_ID)).thenReturn(order);
        when(gateway.createPaymentIntent(12_345L, String.valueOf(ORDER_ID), "attempt-stable"))
                .thenAnswer(invocation -> {
                    order.setStatus(OrderStatusEnum.CANCELLED.getCode());
                    return snapshot;
                });
        when(transactions.bindPaymentIntentToOrder(ORDER_ID, snapshot))
                .thenReturn(paymentAttempt("pi_cancel_race", "requires_payment_method"));

        StripePaymentView result = service.createOrReuseOrderPaymentIntent(ORDER_ID);

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), result.orderStatus());
        assertNull(result.clientSecret());
    }

    @Test
    void webhookRequiresVerifiedTestEventAndIgnoresUnrelatedEventTypes() {
        when(gateway.verifySignatureAndParsePaymentEvent("signed-raw-body", "signature"))
                .thenReturn(new StripePaymentEvent("evt_test_unrelated", "charge.succeeded", false, null));

        service.verifyAndProcessWebhookEvent("signed-raw-body", "signature");

        verify(gateway).verifySignatureAndParsePaymentEvent("signed-raw-body", "signature");
        verifyNoInteractions(transactions);
    }

    @Test
    void refusedCancellationFallsBackToTheRealRemoteStateInsteadOfFailing() {
        Order order = expiredOrder(new BigDecimal("123.45"));
        OrderStripePayment payment = paymentAttempt("pi_late", "requires_action");
        StripePaymentSnapshot stillProcessing = snapshot("pi_late", "requires_action", 0L);
        StripePaymentSnapshot actuallyPaid = snapshot("pi_late", "succeeded", 12_345L);
        when(transactions.requireOrderOwnedByUser(ORDER_ID, USER_ID)).thenReturn(order);
        when(transactions.beginOrderPayment(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.claimPaymentAttempt(ORDER_ID)).thenReturn(payment);
        when(transactions.findOrderPaymentForUser(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.findOrderForRecovery(ORDER_ID)).thenReturn(order);
        // 第一次是恢复时取状态，第二次是"取消被拒"之后重新取真实状态。
        when(gateway.retrievePaymentIntent("pi_late")).thenReturn(stillProcessing, actuallyPaid);
        when(transactions.syncPaymentStatusFromSnapshot(stillProcessing)).thenReturn(payment);
        when(transactions.syncPaymentStatusFromSnapshot(actuallyPaid))
                .thenReturn(paymentAttempt("pi_late", "succeeded"));
        when(gateway.cancelPaymentIntent(eq("pi_late"), anyString()))
                .thenThrow(new StripeGatewayException(StripeGatewayException.Kind.REQUEST_REJECTED,
                        "payment_intent_unexpected_state", 400, null));

        service.createOrReuseOrderPaymentIntent(ORDER_ID);

        // 拒绝取消是确定的业务结论：不抛给上层，改为用真实状态收场。
        verify(gateway, times(2)).retrievePaymentIntent("pi_late");
        verify(transactions).syncPaymentStatusFromSnapshot(actuallyPaid);
    }

    @Test
    void cancellationTimeoutPropagatesBecauseTheRemoteStateIsUnknown() {
        Order order = expiredOrder(new BigDecimal("123.45"));
        OrderStripePayment payment = paymentAttempt("pi_late", "requires_action");
        StripePaymentSnapshot stillProcessing = snapshot("pi_late", "requires_action", 0L);
        when(transactions.requireOrderOwnedByUser(ORDER_ID, USER_ID)).thenReturn(order);
        when(transactions.beginOrderPayment(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.claimPaymentAttempt(ORDER_ID)).thenReturn(payment);
        when(transactions.findOrderPaymentForUser(ORDER_ID, USER_ID)).thenReturn(payment);
        when(transactions.findOrderForRecovery(ORDER_ID)).thenReturn(order);
        when(gateway.retrievePaymentIntent("pi_late")).thenReturn(stillProcessing);
        when(transactions.syncPaymentStatusFromSnapshot(stillProcessing)).thenReturn(payment);
        when(gateway.cancelPaymentIntent(eq("pi_late"), anyString()))
                .thenThrow(new StripeGatewayException(StripeGatewayException.Kind.OUTCOME_UNKNOWN));

        assertThrows(StripeGatewayException.class, () -> service.createOrReuseOrderPaymentIntent(ORDER_ID));

        // 结果未知时必须留给下一轮恢复核实，不能假装成功或假装失败。
        verify(gateway, times(1)).retrievePaymentIntent("pi_late");
    }

    private static Order payableOrder(BigDecimal totalAmount) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setTotalAmount(totalAmount);
        order.setCreateTime(LocalDateTime.now().minusMinutes(1));
        order.setStatus(OrderStatusEnum.PAYING.getCode());
        return order;
    }

    /** 已过 30 分钟支付窗口的订单，用于触发主动取消分支。 */
    private static Order expiredOrder(BigDecimal totalAmount) {
        Order order = payableOrder(totalAmount);
        order.setCreateTime(LocalDateTime.now().minusMinutes(31));
        return order;
    }

    private static OrderStripePayment paymentAttempt(String intentId, String status) {
        OrderStripePayment payment = new OrderStripePayment();
        payment.setOrderId(ORDER_ID);
        payment.setPaymentIntentId(intentId);
        payment.setAmountMinor(12_345L);
        payment.setCurrency("cny");
        payment.setStripeStatus(status);
        payment.setIdempotencyKey("attempt-stable");
        // 幂等有效期由 createTime + 23h 推导，不再持久化单独字段。
        payment.setCreateTime(LocalDateTime.now());
        return payment;
    }

    private static StripePaymentSnapshot snapshot(String id, String status, long amountReceived) {
        return snapshot(id, status, amountReceived, 12_345L);
    }

    private static StripePaymentSnapshot snapshot(String id, String status, long amountReceived, long amount) {
        return new StripePaymentSnapshot(id, amount, amountReceived, "cny", status, false,
                String.valueOf(ORDER_ID), "payment-client-secret");
    }
}
