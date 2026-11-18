package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.RefundSyncResult;
import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Gateway.StripeWebhookEvent;
import org.example.takeout.Payment.Mapper.OrderStripeRefundAttemptMapper;
import org.example.takeout.Payment.VO.RefundExecution;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeRefundService {
    private final StripeRefundTransactionService refundTransactionService;
    private final StripeRefundGateway refundGateway;
    private final OrderStripeRefundAttemptMapper attemptMapper;

    @Transactional
    public RefundPreparation prepareRefund(Long orderId, Long merchantId) {
        return refundTransactionService.prepareRefund(orderId, merchantId);
    }

    @Transactional
    public RefundPreparation prepareTimeoutRefund(Long orderId) {
        return refundTransactionService.prepareTimeoutRefund(orderId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refundRejectedOrder(Long orderId) {
        executeCurrentAttempt(orderId, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recoverRefund(OrderStripeRefundAttempt scannedAttempt) {
        executeCurrentAttempt(scannedAttempt.getOrderId(), scannedAttempt.getId());
    }

    private void executeCurrentAttempt(Long orderId, Long scannedAttemptId) {
        RefundExecution execution = refundTransactionService.prepareRefundExecution(orderId, scannedAttemptId);
        if (execution == null) {
            return;
        }

        // 准备事务已提交。后续网络结果、异常和退避都归属于本次实际执行的 Attempt。
        OrderStripeRefundAttempt attempt = execution.attempt();
        RefundSyncResult result;
        try {
            StripeRefundSnapshot snapshot = attempt.getRefundId() == null
                    ? refundGateway.createRefund(execution.paymentIntentId(), execution.amountMinor(), attempt.getIdempotencyKey())
                    : refundGateway.retrieveRefund(attempt.getRefundId());
            result = refundTransactionService.syncRefundStatus(orderId, attempt.getId(), snapshot);
        } catch (RuntimeException failure) {
            if (scannedAttemptId != null) {
                try {
                    refundTransactionService.scheduleConflictRetry(orderId, attempt.getId());
                } catch (RuntimeException schedulingFailure) {
                    failure.addSuppressed(schedulingFailure);
                }
            }
            throw failure;
        }
        if (result == RefundSyncResult.CONFLICT) {
            refundTransactionService.scheduleConflictRetry(orderId, attempt.getId());
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public void processWebhookRefundEvent(StripeWebhookEvent event) {
        if (refundTransactionService.isRefundEventProcessed(event.id())) {
            return;
        }
        StripeRefundSnapshot eventRefund = event.refund();
        OrderStripeRefundAttempt attempt = attemptMapper.selectAttemptByRefundId(eventRefund.refundId());
        if (attempt == null && eventRefund.attemptKey() != null) {
            attempt = attemptMapper.selectAttemptByIdempotencyKey(eventRefund.attemptKey());
        }
        if (attempt == null || (attempt.getRefundId() != null && !attempt.getRefundId().equals(eventRefund.refundId()))) {
            log.warn("退款事件无法匹配本地 Attempt，eventId={}, refundId={}", event.id(), eventRefund.refundId());
            return;
        }

        StripeRefundSnapshot snapshot = refundGateway.retrieveRefund(eventRefund.refundId());
        boolean needsMetadataCheck = attempt.getRefundId() == null
                && attempt.getIdempotencyKey().startsWith(PaymentPolicy.REFUND_ATTEMPT_KEY_PREFIX);
        if (!eventRefund.refundId().equals(snapshot.refundId())
                || (needsMetadataCheck && !Objects.equals(attempt.getIdempotencyKey(), snapshot.attemptKey()))) {
            throw new IllegalStateException("Stripe 退款回查结果与事件所属 Attempt 不一致");
        }
        try {
            RefundSyncResult result = refundTransactionService.syncWebhookRefundStatus(
                    attempt.getOrderId(), attempt.getId(), snapshot, event.id(), event.type());
            if (result == RefundSyncResult.CONFLICT) {
                log.warn("退款事件与 Attempt 终态冲突，orderId={}, attemptId={}, eventId={}",
                        attempt.getOrderId(), attempt.getId(), event.id());
            }
        } catch (DuplicateKeyException concurrentWrite) {
            if (!refundTransactionService.isRefundEventProcessed(event.id())) {
                throw concurrentWrite;
            }
        }
    }
}
