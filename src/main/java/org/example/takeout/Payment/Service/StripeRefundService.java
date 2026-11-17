package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.RefundSyncResult;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Gateway.StripeWebhookEvent;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

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

    /**
     * 处理一条已验签的退款 Webhook 事件。
     * <p>边界刻意保持三段：本地去重与归属判断只有读操作，远端 {@code retrieveRefund} 是唯一一次
     * HTTP 调用且发生在事务之外，状态写入与事件回执最后由同一个短事务完成。
     * 因此任何一步失败都不会留下"已处理"的回执，Stripe 重投仍能被正常处理。</p>
     */
    @Transactional(propagation = Propagation.NEVER)
    public void processWebhookRefundEvent(StripeWebhookEvent event) {
        StripeRefundSnapshot eventRefund = event.refund();
        if (refundTransactionService.isRefundEventProcessed(event.id())) {
            log.info("退款事件已处理，忽略重复投递: eventId={}, refundId={}",
                    event.id(), eventRefund.refundId());
            return;
        }

        OrderStripeRefund local = refundTransactionService.findRefundForEvent(
                eventRefund.refundId(), eventRefund.paymentIntentId());
        if (local == null) {
            // 不是本地发起的退款（例如后台手工退款）：既不写回执，也不改本地状态。
            log.warn("退款事件无法归属到本地退款记录，忽略: eventId={}, refundId={}, paymentIntentId={}",
                    event.id(), eventRefund.refundId(), eventRefund.paymentIntentId());
            return;
        }
        if (local.getRefundId() != null && !local.getRefundId().equals(eventRefund.refundId())) {
            log.warn("退款事件指向另一笔退款对象，忽略: eventId={}, eventRefundId={}, localRefundId={}",
                    event.id(), eventRefund.refundId(), local.getRefundId());
            return;
        }

        // 事件载荷停留在投递时刻，回查一次拿当前真相；网络调用不进事务。
        StripeRefundSnapshot snapshot = refundGateway.retrieveRefund(eventRefund.refundId());

        requireRefundBelongsToOrder(local, snapshot);

        final RefundSyncResult result;
        try {
            result = refundTransactionService.syncWebhookRefundStatus(
                    local.getOrderId(), snapshot, event.id(), event.type());
        } catch (DuplicateKeyException concurrentDelivery) {
            // 并发投递：另一条线程已处理并提交同一事件，本次不再重复写状态。
            log.info("退款事件已被并发投递处理: eventId={}, refundId={}",
                    event.id(), eventRefund.refundId());
            return;
        }

        if (result == RefundSyncResult.CONFLICT) {
            log.warn("退款事件与本地终态冲突，保留本地结论并转人工: orderId={}, eventId={}",
                    local.getOrderId(), event.id());
        }
    }

    /**
     * 事件里的退款对象必须与本地退款记录、本地支付单对得上，否则不能拿来改本地状态。
     * <p>这里是进事务前的预检（读操作不加锁），事务内还会用行锁再校验一次金额与币种。</p>
     */
    private void requireRefundBelongsToOrder(OrderStripeRefund local, StripeRefundSnapshot snapshot) {
        OrderStripePayment payment = orderStripePaymentMapper.selectPaymentByOrderId(local.getOrderId());
        if (payment == null
                || payment.getPaymentIntentId() == null
                || !Objects.equals(payment.getPaymentIntentId(), snapshot.paymentIntentId())) {
            throw new BusinessException(ResultCodeEnum.PAYMENT_ERROR, "退款关联的支付对象不匹配");
        }
        if (!Objects.equals(local.getAmountMinor(), snapshot.amountMinor())
                || !Objects.equals(local.getCurrency(), snapshot.currency())) {
            throw new BusinessException(ResultCodeEnum.PAYMENT_ERROR, "退款金额或币种与本地记录不一致");
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
