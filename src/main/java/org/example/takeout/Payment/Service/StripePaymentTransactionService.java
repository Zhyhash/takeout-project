package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Payment.Domain.Money;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.StripeStatus;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentEvent;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.StripePaymentEventMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Local payment coordination only. Lock order is always orders -> order_stripe_payments. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripePaymentTransactionService {
    @Value("${payment.stripe.recovery-max-attempts:" + PaymentPolicy.MAX_RECOVERY_ATTEMPTS + "}")
    private int maxAttempts = PaymentPolicy.MAX_RECOVERY_ATTEMPTS;
    private final OrderStripePaymentMapper orderPaymentMapper;
    private final StripePaymentEventMapper paymentEventMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** Eligibility CAS, payment record and immutable create parameters commit before any HTTP call. */
    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment beginOrderPayment(Long orderId, Long userId) {
        Order before = requireOrderOwnedByUser(orderId, userId);
        // 先校验原始金额，再做状态 CAS：保持原有的失败顺序。
        Money.ofYuan(before.getTotalAmount());
        //这里只是将order状态改变，不是真正的改变stripe
        Order order = orderService.beginStripePayment(orderId, userId);

        long amountMinor = Money.ofYuan(order.getTotalAmount()).minorUnits();

        OrderStripePayment payment = orderPaymentMapper.selectPaymentByOrderIdForUpdate(orderId);
        if (payment == null) {
            if (OrderStatusEnum.PAYING.getCode().equals(before.getStatus())) {
                throw paymentValidationError("支付中订单缺少支付记录，需人工核实，禁止创建另一笔支付");
            }
            OrderStripePayment candidate = new OrderStripePayment();
            candidate.setOrderId(orderId);
            candidate.setAmountMinor(amountMinor);
            candidate.setCurrency(Money.CURRENCY);
            candidate.setIdempotencyKey(UUID.randomUUID().toString());
            orderPaymentMapper.insertPaymentIfAbsent(candidate);
            payment = orderPaymentMapper.selectPaymentByOrderIdForUpdate(orderId);
        }

        if (payment == null || !Objects.equals(payment.getAmountMinor(), amountMinor)
                || !Money.CURRENCY.equals(payment.getCurrency())) {
            throw paymentValidationError("订单与已有支付金额或币种不一致");
        }
        return payment;
    }

    /** First binding and all subsequent results use exactly the same local application rules. */
    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment bindPaymentIntentToOrder(Long orderId, StripePaymentSnapshot snapshot) {
        return applySnapshot(orderId, snapshot, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment syncPaymentStatusFromSnapshot(StripePaymentSnapshot snapshot) {
        return applySnapshot(requireBoundPayment(snapshot).getOrderId(), snapshot, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment recordWebhookPaymentEvent(StripePaymentEvent event) {
        StripePaymentSnapshot snapshot = event.payment();
        OrderStripePayment payment = orderPaymentMapper.selectPaymentByPaymentIntentId(snapshot.id());
        if (payment == null) {
            Long referencedOrderId = null;
            try { referencedOrderId = Long.valueOf(snapshot.orderReference()); }
            catch (NumberFormatException ignored) { /* Unrelated account activity is not a local order. */ }
            if (referencedOrderId != null && orderPaymentMapper.selectPaymentByOrderId(referencedOrderId) != null) {
                throw paymentValidationError("支付尚未关联，等待恢复后重试事件");
            }
            return null; // Do not insert a receipt or associate arbitrary signed Stripe objects.
        }
        return applySnapshot(payment.getOrderId(), snapshot, event.id(), event.type());
    }

    private OrderStripePayment requireBoundPayment(StripePaymentSnapshot snapshot) {
        OrderStripePayment payment = snapshot == null ? null
                : orderPaymentMapper.selectPaymentByPaymentIntentId(snapshot.id());
        // Do not acknowledge an unbound event: recovery will bind using the original key, then Stripe can retry.
        if (payment == null) throw paymentValidationError("支付尚未关联，等待恢复后重试事件");
        return payment;
    }

    /** 处理事务回滚后，记录失败并推迟仍待对账的支付。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordWebhookFailure(StripePaymentSnapshot snapshot) {
        OrderStripePayment found = orderPaymentMapper.selectPaymentByPaymentIntentId(snapshot.id());
        if (found == null) return; // Unbound local attempts are already pending recovery by their original key.
        orderMapper.selectOrderForUpdate(found.getOrderId());
        OrderStripePayment current = orderPaymentMapper.selectPaymentByOrderIdForUpdate(found.getOrderId());
        if (current == null || !Objects.equals(snapshot.id(), current.getPaymentIntentId())) return;
        log.error("Stripe webhook processing failed: orderId={}, reconciliationState={}",
                current.getOrderId(), current.getReconciliationState());
        if (!ReconciliationState.isPending(current.getReconciliationState())) return;

        LocalDateTime nextRetryAt = orderPaymentMapper.selectDatabaseTime()
                .plusSeconds(PaymentPolicy.retryDelaySeconds(current.getRetryCount()));
        if (current.getNextRetryAt() == null || current.getNextRetryAt().isBefore(nextRetryAt)) {
            orderPaymentMapper.updateReconciliation(current.getOrderId(), ReconciliationState.PENDING.code(),
                    nextRetryAt);
        }
    }

    private OrderStripePayment applySnapshot(
            Long orderId,
            StripePaymentSnapshot snapshot,
            String eventId,
            String eventType) {

        Order order = orderMapper.selectOrderForUpdate(orderId);
        OrderStripePayment payment =
                orderPaymentMapper.selectPaymentByOrderIdForUpdate(orderId);

        validatePaymentSnapshotMatchesOrder(order, payment, snapshot);

        if (eventId != null
                && paymentEventMapper.countEventsByEventId(eventId) != 0) {
            return payment;
        }

        String previous = payment.getStripeStatus();
        String status = snapshot.status();
        StripeStatus incoming = StripeStatus.parseOrNull(status);

        // 两个互斥终态同时出现时，无论哪个方向，重投都不会改变结论，
        // 因此不抛异常（否则 Stripe 会连续重投到放弃），而是保留先到的终态并转人工核实。
        if (isTerminalConflict(previous, status)) {
            return flagTerminalConflict(orderId, snapshot, eventId, eventType, previous, status);
        }

        // 终态不可回退，与快照到达顺序无关。
        boolean previousIsTerminal =
                StripeStatus.SUCCEEDED.matches(previous)
                        || StripeStatus.CANCELED.matches(previous);

        boolean apply = !previousIsTerminal;

        if (apply || Objects.equals(previous, status)) {

            if (incoming != null && incoming.isSucceeded()
                    && !orderService.confirmOrderPayment(orderId, order.getUserId())) {
                throw paymentValidationError("Stripe 已成功但订单无法确认，保留待恢复状态");
            }

            if (incoming != null && incoming.isCanceled()
                    && !orderService.cancelConfirmedStripePayment(orderId)) {
                throw paymentValidationError("Stripe 已取消但订单无法取消，保留待恢复状态");
            }

            if (orderPaymentMapper.updatePaymentIntentAndStatus(
                    orderId, snapshot.id(), status) != 1) {
                throw paymentValidationError("支付关联保存失败");
            }

            boolean terminal = incoming != null && incoming.isTerminal();

            orderPaymentMapper.updateReconciliation(
                    orderId,
                    (terminal
                            ? ReconciliationState.RECONCILED
                            : ReconciliationState.PENDING).code(),
                    terminal
                            ? null
                            : orderPaymentMapper.selectDatabaseTime()
                            .plusSeconds(PaymentPolicy.retryDelaySeconds(
                                    payment.getRetryCount()))
            );
        }

        // 只有支付状态与订单状态均成功同步，才记录事件。
        // 任何异常都应使当前事务回滚。
        if (eventId != null) {
            paymentEventMapper.insertPaymentEvent(
                    eventId, snapshot.id(), eventType);
        }

        return orderPaymentMapper.selectPaymentByOrderId(orderId);
    }

    /**
     * 本地终态与远端终态互斥：只可能来自外部改写、人工改库，或我们对 Stripe 状态机的理解有误，
     * 都属于必须人工介入的情况。两个方向同等对待，不因为"哪个方向更严重"而使用不同手段。
     */
    private static boolean isTerminalConflict(String previous, String incoming) {
        return (StripeStatus.SUCCEEDED.matches(previous) && StripeStatus.CANCELED.matches(incoming))
                || (StripeStatus.CANCELED.matches(previous) && StripeStatus.SUCCEEDED.matches(incoming));
    }

    /**
     * 记录一次终态冲突：不改写支付状态，也不联动订单状态，只把本地协调状态转为人工复核。
     * <p>事件回执照常记录，表示"已收到并已交人工处理"。这是有意为之——本地终态不会因为
     * Stripe 重投而改变，反复投递同一事件也无法解决这次冲突。</p>
     */
    private OrderStripePayment flagTerminalConflict(Long orderId, StripePaymentSnapshot snapshot,
                                                    String eventId, String eventType,
                                                    String previous, String incoming) {
        orderPaymentMapper.updateReconciliation(orderId, ReconciliationState.REVIEW.code(), null);
        if (eventId != null) {
            paymentEventMapper.insertPaymentEvent(eventId, snapshot.id(), eventType);
        }
        log.error("Stripe 终态冲突，保留先到状态并转人工: orderId={}, previous={}, incoming={}",
                orderId, previous, incoming);
        return orderPaymentMapper.selectPaymentByOrderId(orderId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findRecoveryOrderIds(int limit) {
        return orderPaymentMapper.selectRecoveryOrderIds(limit);
    }

    /**
     * 领取一次恢复尝试。
     * <p>这里没有跨实例租约：CAS 把 {@code next_retry_at} 推到未来，后续扫描的
     * {@code next_retry_at <= now} 条件随即不成立，因此同一时刻只有一个调用者能成功占位。
     * 进程崩溃后等待一个退避周期即可被重新领取，与原租约到期的时间尺度一致。</p>
     *
     * @return 已占位的支付记录（含本次的 {@code retryCount} 与 {@code nextRetryAt}）；
     *         未抢到、已完结或需人工复核时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderStripePayment claimPaymentAttempt(Long orderId) {
        // 在同一行锁内判断状态与预算，避免覆盖并发同步提交的终态。
        OrderStripePayment payment = orderPaymentMapper.selectPaymentByOrderIdForUpdate(orderId);
        if (payment == null || !ReconciliationState.isPending(payment.getReconciliationState())) {
            return null;
        }

        // 与库内到期时间使用同一个时钟，避免 JVM 与数据库精度差异导致首次尝试被跳过。
        LocalDateTime now = orderPaymentMapper.selectDatabaseTime();
        LocalDateTime scheduledRetryAt = payment.getNextRetryAt();
        if (scheduledRetryAt == null || scheduledRetryAt.isAfter(now)) {
            return null;
        }
        int retryCount = payment.getRetryCount() == null ? 0 : payment.getRetryCount();

        // 先做风控裁决再占位，避免"刚抢到就必须放弃"的无谓写入。
        if (retryCount >= Math.max(1, maxAttempts)) {
            markForReview(payment, "RETRY_LIMIT_REACHED");
            return null;
        }
        if (payment.getPaymentIntentId() == null && !isIdempotencyWindowUsable(payment, now)) {
            markForReview(payment, "CREATE_RESULT_UNKNOWN_IDEMPOTENCY_EXPIRED");
            return null;
        }

        LocalDateTime nextRetryAt = now.plus(PaymentPolicy.attemptHold(retryCount));
        if (orderPaymentMapper.claimAttempt(orderId, now, nextRetryAt) != 1)
            return null;

        payment.setRetryCount(retryCount + 1);
        payment.setNextRetryAt(nextRetryAt);
        return payment;
    }

    /**
     * 幂等键是否仍可用于创建：Stripe 侧至少保留 24 小时，本地保守按 23 小时计，
     * 由记录创建时间推导，不再单独持久化一个过期时间列。
     */
    private static boolean isIdempotencyWindowUsable(OrderStripePayment payment, LocalDateTime now) {
        return payment.getCreateTime() != null
                && payment.getCreateTime().plus(PaymentPolicy.IDEMPOTENCY_TTL)
                        .isAfter(now.plus(PaymentPolicy.IDEMPOTENCY_SAFETY_MARGIN));
    }

    /**
     * 创建 PaymentIntent 前的最后一道核对：确认这笔支付仍在等待处理、尚未被绑定，
     * 且幂等窗口足以覆盖本次网关调用。用于覆盖"领取后线程被挂起"的情况。
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean canCreatePaymentIntent(OrderStripePayment attempt) {
        OrderStripePayment current = orderPaymentMapper.selectPaymentByOrderId(attempt.getOrderId());
        if (current == null
                || !ReconciliationState.isPending(current.getReconciliationState())
                || current.getPaymentIntentId() != null) {
            return false;
        }
        return isIdempotencyWindowUsable(current, orderPaymentMapper.selectDatabaseTime());
    }

    private void markForReview(OrderStripePayment payment, String reason) {
        orderPaymentMapper.updateReconciliation(payment.getOrderId(), ReconciliationState.REVIEW.code(), null);
        log.error("Stripe payment requires manual review: orderId={}, reason={}", payment.getOrderId(), reason);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public OrderStripePayment findOrderPaymentForUser(Long orderId, Long userId) {
        requireOrderOwnedByUser(orderId, userId);
        return orderPaymentMapper.selectPaymentByOrderId(orderId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Order findOrderForRecovery(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw paymentValidationError("支付订单不存在");
        return order;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Order requireOrderOwnedByUser(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || userId == null || !userId.equals(order.getUserId())) {
            throw paymentValidationError("订单不存在或无权操作");
        }
        return order;
    }

    private static void validatePaymentSnapshotMatchesOrder(Order order, OrderStripePayment payment,
                                                            StripePaymentSnapshot snapshot) {
        if (order == null || payment == null || snapshot == null || snapshot.livemode() || snapshot.id() == null
                || !snapshot.id().startsWith(PaymentPolicy.PAYMENT_INTENT_PREFIX)
                || !Money.CURRENCY.equals(snapshot.currency())
                || !Money.CURRENCY.equals(payment.getCurrency())
                || !StripeStatus.isKnown(snapshot.status())
                || !Objects.equals(payment.getAmountMinor(), snapshot.amount())
                || !Objects.equals(Money.ofYuan(order.getTotalAmount()).minorUnits(), snapshot.amount())
                || !Objects.equals(payment.getOrderId().toString(), snapshot.orderReference())
                || (payment.getPaymentIntentId() != null && !Objects.equals(payment.getPaymentIntentId(), snapshot.id()))
                || (StripeStatus.SUCCEEDED.matches(snapshot.status())
                    && !Objects.equals(snapshot.amountReceived(), payment.getAmountMinor()))
                || (StripeStatus.CANCELED.matches(snapshot.status())
                    && !Long.valueOf(0).equals(snapshot.amountReceived()))) {
            throw paymentValidationError("Stripe 测试支付结果与订单不一致");
        }
    }

    private static BusinessException paymentValidationError(String message) {
        return new BusinessException(ResultCodeEnum.BUSINESS_ERROR, message);
    }
}
