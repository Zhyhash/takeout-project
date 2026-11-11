package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
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
    public void refundRejectedOrder(Long orderId) {
        OrderStripeRefund refund =
               orderStripeRefundMapper.selectPaymentByOrderId(orderId);

        // 已完成退款，直接返回
        if ("succeeded".equals(refund.getStripeStatus())
                && ReconciliationState.COMPLETE.code()
                .equals(refund.getReconciliationState())) {
            return;
        }

        StripeRefundSnapshot snapshot;

        if (refund.getRefundId() != null) {
            snapshot = refundGateway.retrieveRefund(refund.getRefundId());
        } else {
            OrderStripePayment payment =
                    orderStripePaymentMapper.selectPaymentByOrderId(orderId);

            snapshot = refundGateway.createRefund(
                    payment.getPaymentIntentId(),
                    refund.getAmountMinor(),
                    refund.getIdempotencyKey()
            );
        }

        refundTransactionService.syncRefundStatus(orderId, snapshot);
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
            refundGateway.retrieveRefund(refundId);
        }else {
            OrderStripePayment orderStripePayment = orderStripePaymentMapper.selectPaymentByOrderId(orderStripeRefund.getOrderId());
            String paymentIntentId = orderStripePayment.getPaymentIntentId();
            snapshot = refundGateway.createRefund(paymentIntentId,
                    orderStripePayment.getAmountMinor(),
                    orderStripeRefund.getIdempotencyKey());
            refundTransactionService.syncRefundStatus(
                    orderStripeRefund.getOrderId(),
                    snapshot
            );
        }

    }
}
