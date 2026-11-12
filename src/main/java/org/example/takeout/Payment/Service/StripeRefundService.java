package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.RefundSyncResult;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeRefundService {
    private final StripeRefundTransactionService refundTransactionService;
    private final StripeRefundGateway refundGateway;
    private final OrderStripePaymentMapper orderStripePaymentMapper;
    private final OrderStripeRefundMapper orderStripeRefundMapper;


    @Transactional
    public RefundPreparation prepareRefund(Long orderId, Long merchantId){
        return refundTransactionService.
                prepareRefund(orderId, merchantId);

    }
    //TODO：评估将 order_stripe_refunds 拆分为退款业务主表与 Refund Attempt 从表，支持多次退款、保留历史记录，并分离业务重试与对账重试。

    /** 商家超时未接单的系统取消入口，与商家拒单共用后续退款执行逻辑。 */
    @Transactional
    public RefundPreparation prepareTimeoutRefund(Long orderId) {
        return refundTransactionService.prepareTimeoutRefund(orderId);
    }

    public void refundRejectedOrder(Long orderId) {
        OrderStripeRefund refund =
               orderStripeRefundMapper.selectRefundmentByOrderId(orderId);

        if (refund == null) {
            throw new IllegalStateException("退款记录不存在");
        }

        // 人工复核状态禁止自动执行
        if (ReconciliationState.REVIEW.matches(
                refund.getReconciliationState())) {
            return;
        }
        // 已完成对账的终态不再由此入口重复处理
        if (ReconciliationState.RECONCILED.matches(
                refund.getReconciliationState())
                && isTerminal(refund.getStripeStatus())) {
            return;
        }

        StripeRefundSnapshot snapshot;

        if (refund.getRefundId() != null) {
            snapshot = refundGateway.retrieveRefund(refund.getRefundId());
        } else {
            OrderStripePayment payment =
                    orderStripePaymentMapper.selectPaymentByOrderId(orderId);

            LocalDateTime createTime = refund.getCreateTime();
            if (stopIfIdempotencyWindowExpired(refund, createTime))
                return;

            snapshot = refundGateway.createRefund(
                    payment.getPaymentIntentId(),
                    refund.getAmountMinor(),
                    refund.getIdempotencyKey()
            );
        }

        RefundSyncResult refundSyncResult = refundTransactionService.syncRefundStatus(orderId, snapshot);
        if (refundSyncResult == RefundSyncResult.CONFLICT) {
            refundTransactionService.scheduleConflictRetry(orderId);
        }
    }

    public void recoverRefund(OrderStripeRefund orderStripeRefund){
        if (!ReconciliationState.PENDING.code()
                .equals(orderStripeRefund.getReconciliationState())) {
            return;
        }

        String refundId = orderStripeRefund.getRefundId();
        StripeRefundSnapshot snapshot;
        //恢复任务绑定
        if (refundId != null) {
            snapshot = refundGateway.retrieveRefund(refundId);
        }else {
            OrderStripePayment orderStripePayment = orderStripePaymentMapper.selectPaymentByOrderId(orderStripeRefund.getOrderId());
            String paymentIntentId = orderStripePayment.getPaymentIntentId();

            LocalDateTime createTime = orderStripeRefund.getCreateTime();
            if (stopIfIdempotencyWindowExpired(orderStripeRefund, createTime))
                return;

            snapshot = refundGateway.createRefund(paymentIntentId,
                    orderStripeRefund.getAmountMinor(),
                    orderStripeRefund.getIdempotencyKey());

        }
        RefundSyncResult refundSyncResult = refundTransactionService.syncRefundStatus(
                orderStripeRefund.getOrderId(),
                snapshot
        );
        if (refundSyncResult == RefundSyncResult.CONFLICT) {
            refundTransactionService.scheduleConflictRetry(orderStripeRefund.getOrderId());
        }
    }

    private boolean stopIfIdempotencyWindowExpired(OrderStripeRefund orderStripeRefund, LocalDateTime createTime) {
        LocalDateTime now = orderStripePaymentMapper.selectDatabaseTime();
        if (now.isBefore(createTime.plus(PaymentPolicy.IDEMPOTENCY_TTL))) {
            return false;
        }

        refundTransactionService.markReconciliationReview(orderStripeRefund.getOrderId());
        log.warn(
                "退款恢复停止：幂等安全窗口已过期，orderId={}, refundId={}, createTime={}",
                orderStripeRefund.getOrderId(),
                orderStripeRefund.getRefundId(),
                createTime
        );
        return true;
    }

    private boolean isTerminal(String status) {
        return "succeeded".equals(status)
                || "failed".equals(status)
                || "canceled".equals(status);
    }
}
