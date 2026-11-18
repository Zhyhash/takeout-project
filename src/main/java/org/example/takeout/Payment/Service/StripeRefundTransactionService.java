package org.example.takeout.Payment.Service;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.RefundStatus;
import org.example.takeout.Payment.Domain.RefundSyncResult;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;
import org.example.takeout.Payment.Config.StripeRefundRetryPolicy;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundAttemptMapper;
import org.example.takeout.Payment.Mapper.StripeRefundEventMapper;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.example.takeout.Payment.VO.RefundExecution;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeRefundTransactionService {
    // TODO: 支持 Stripe Refund requires_action 状态，
    // 明确客户行动、取消退款及本地对账状态的处理规则。
    private static final Set<String> REFUND_STATUSES =
            Set.of("pending", "succeeded", "failed", "canceled");

    private final OrderStripeRefundMapper orderStripeRefundMapper;
    private final OrderStripeRefundAttemptMapper refundAttemptMapper;

    private final OrderMapper orderMapper;
    private final OrderStripePaymentMapper orderStripePaymentMapper;
    private final StripeRefundEventMapper refundEventMapper;
    private final StripeRefundRetryPolicy refundRetryPolicy;

    @Transactional
    public RefundPreparation prepareRefund(Long orderId, Long merchantId) {
        return prepareRefund(orderId, merchantId, false);
    }

    /**
     * 商家超时未接单的系统取消入口。
     * <p>与商家拒单共用同一段「抢占 REFUNDING + 建退款记录」逻辑，只有两点不同：
     * 没有商家身份可校验，抢占条件换成支付时间已超时。</p>
     */
    @Transactional
    public RefundPreparation prepareTimeoutRefund(Long orderId) {
        return prepareRefund(orderId, null, true);
    }

    private RefundPreparation prepareRefund(Long orderId, Long merchantId, boolean timeoutCancellation) {
        Order order = orderMapper.selectOrderForUpdate(orderId);
        if (order == null || (merchantId != null && !Objects.equals(order.getMerchantId(), merchantId))) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    merchantId == null ? "订单不存在，无法超时取消" : "订单不存在或者不属于商家，无法取消");
        }
        if (Objects.equals(order.getStatus(), OrderStatusEnum.REFUNDING.getCode())
                || Objects.equals(order.getStatus(), OrderStatusEnum.REFUNDED.getCode())) {
            // 幂等，查询数据库里面是否存在该数据
            OrderStripeRefund orderStripeRefund = orderStripeRefundMapper.selectById(orderId);
            if (orderStripeRefund != null) {
                return new RefundPreparation(
                        orderStripeRefund,
                        false
                );
            }
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单已处于退款中或已退款状态，但退款记录不存在，请联系管理员处理");
        }
        if (!Objects.equals(order.getStatus(), OrderStatusEnum.PAID.getCode())) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "当前订单状态不允许商家拒单退款"
            );
        }


        OrderStripePayment orderStripePayment = orderStripePaymentMapper.selectPaymentByOrderId(orderId);
        if (orderStripePayment == null
                || StringUtils.isBlank(orderStripePayment.getPaymentIntentId())
                || !"succeeded".equals(orderStripePayment.getStripeStatus())) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "订单不存在可退款的成功 Stripe 支付"
            );
        }

        // 两条入口各用一条条件更新抢占订单，条件互不影响：拒单没有时间限制，超时取消必须已超时
        int update = timeoutCancellation
                ? orderMapper.updateOrderStatusToRefundingOnTimeout(orderId,
                        OrderStatusEnum.REFUNDING.getCode(), OrderStatusEnum.PAID.getCode())
                : orderMapper.updateOrderStatusToRefunding(orderId,
                        OrderStatusEnum.REFUNDING.getCode(), merchantId, OrderStatusEnum.PAID.getCode());
        if (update == 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单状态更新失败，请稍后重试");
        }


        OrderStripeRefund orderStripeRefund = new OrderStripeRefund();
        orderStripeRefund.setOrderId(orderId);
        orderStripeRefund.setAmountMinor(orderStripePayment.getAmountMinor());
        orderStripeRefund.setCurrency("cny");
        orderStripeRefund.setStatus(RefundStatus.PENDING.code());
        orderStripeRefundMapper.insert(orderStripeRefund);

        insertRefundAttempt(orderId);
        return new RefundPreparation(orderStripeRefund, true);
    }

    /**
     * 在一次短事务内决定应恢复当前请求、建立新的退款请求，还是停止自动处理。
     * expectedAttemptId 用于定时扫描，避免基于过期扫描结果操作另一条新 Attempt。
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundExecution prepareRefundExecution(Long orderId, Long expectedAttemptId) {
        Order order = orderMapper.selectOrderForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在，无法继续退款");
        }

        OrderStripeRefund refund = orderStripeRefundMapper.selectByOrderIdForUpdate(orderId);
        if (refund == null) {
            throw new IllegalStateException("退款记录不存在");
        }

        if (!RefundStatus.PENDING.matches(refund.getStatus())) {
            return null;
        }
        OrderStripeRefundAttempt attempt = refundAttemptMapper.selectLatestAttemptByOrderIdForUpdate(orderId);
        if (expectedAttemptId != null && (attempt == null || !expectedAttemptId.equals(attempt.getId()))) {
            return null;
        }
        if (attempt == null || !Objects.equals(order.getStatus(), OrderStatusEnum.REFUNDING.getCode())) {
            markRefundForReview(refund);
            return null;
        }

        boolean reconciled = ReconciliationState.RECONCILED.matches(attempt.getReconciliationState());
        if (reconciled) {
            if (StringUtils.isBlank(attempt.getRefundId())
                    || !allowsNewAttempt(attempt.getStripeStatus(), attempt.getFailureReason())) {
                markRefundForReview(refund);
                return null;
            }
        } else {
            if (!ReconciliationState.isPending(attempt.getReconciliationState())
                    || (attempt.getStripeStatus() != null && !REFUND_STATUSES.contains(attempt.getStripeStatus()))
                    || (attempt.getRefundId() == null && isTerminal(attempt.getStripeStatus()))) {
                markRefundForReview(refund);
                return null;
            }
            LocalDateTime now = orderStripePaymentMapper.selectDatabaseTime();
            if (attempt.getRefundId() == null && (attempt.getCreateTime() == null
                    || !now.isBefore(attempt.getCreateTime().plus(PaymentPolicy.IDEMPOTENCY_TTL)))) {
                refundAttemptMapper.markAttemptReconciliationReview(attempt.getId(), attempt.getIdempotencyKey());
                markRefundForReview(refund);
                log.warn("退款幂等窗口已过期，orderId={}, attemptId={}", orderId, attempt.getId());
                return null;
            }
            if (attempt.getNextRetryAt() != null && attempt.getNextRetryAt().isAfter(now)) {
                return null;
            }
        }

        OrderStripePayment payment = orderStripePaymentMapper.selectPaymentByOrderId(orderId);
        if (payment == null || StringUtils.isBlank(payment.getPaymentIntentId())) {
            markRefundForReview(refund);
            return null;
        }

        if (reconciled) {
            attempt = insertRefundAttempt(orderId);
        }
        return new RefundExecution(attempt, refund.getAmountMinor(), payment.getPaymentIntentId());
    }

    /** 用 Stripe 的快照更新指定 Attempt，并在它仍是当前 Attempt 时推进退款业务。 */
    @Transactional(rollbackFor = Exception.class)
    public RefundSyncResult syncRefundStatus(Long orderId, Long attemptId,
                                             StripeRefundSnapshot snapshot) {
        return applyRefundSnapshot(orderId, attemptId, snapshot);
    }

    /**
     * Webhook 专用的同步：退款状态写入与事件回执必须同生共死。
     * <p>回执在同一个事务的最后才写，事务回滚时回执一并消失，Stripe 重投仍能被正常处理；
     * 反过来，只要事务提交，就说明业务状态已经落库，此时才允许把事件标记为已处理。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundSyncResult syncWebhookRefundStatus(Long orderId, Long attemptId,
                                                    StripeRefundSnapshot snapshot,
                                                    String eventId, String eventType) {
        if (StringUtils.isBlank(eventId)) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "退款事件缺少事件 ID");
        }
        // 事务内再查一次：并发投递时事件 ID 的唯一主键是最后一道防线。
        if (refundEventMapper.countEventsByEventId(eventId) != 0) {
            return RefundSyncResult.UNCHANGED;
        }

        RefundSyncResult result = applyRefundSnapshot(orderId, attemptId, snapshot);

        refundEventMapper.insertRefundEvent(eventId, snapshot.refundId(), eventType);
        return result;
    }

    /** 事件去重的第一道快速判断，避免为重复投递再做一次远端回查。 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isRefundEventProcessed(String eventId) {
        return !StringUtils.isBlank(eventId) && refundEventMapper.countEventsByEventId(eventId) != 0;
    }

    private RefundSyncResult applyRefundSnapshot(Long orderId, Long attemptId,
            StripeRefundSnapshot snapshot) {
        if (orderId == null || attemptId == null || snapshot == null) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR,
                    "订单 ID、退款 Attempt ID 与快照不能为空");
        }
        if (StringUtils.isBlank(snapshot.refundId())) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR,
                    "退款快照缺少退款单号");
        }
        Order order = orderMapper.selectOrderForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单不存在，无法同步退款状态");
        }
        OrderStripeRefund orderStripeRefund = orderStripeRefundMapper.selectByOrderIdForUpdate(orderId);

        if (orderStripeRefund == null) {
            throw new IllegalStateException("退款记录不存在");
        }

        OrderStripeRefundAttempt attempt = refundAttemptMapper.selectAttemptByIdForUpdate(attemptId);
        if (attempt == null || !Objects.equals(orderId, attempt.getOrderId())) {
            throw new IllegalStateException("退款 Attempt 不属于指定订单");
        }

        if (attempt.getRefundId() != null
                && !attempt.getRefundId().equals(snapshot.refundId())) {
            throw new IllegalStateException("退款对象不匹配");
        }

        // 金额与币种是退款事实的一部分，任何一次同步都必须与本地发起时一致
        if (!Objects.equals(orderStripeRefund.getAmountMinor(), snapshot.amountMinor())
                || !Objects.equals(orderStripeRefund.getCurrency(), snapshot.currency())) {
            throw new BusinessException(ResultCodeEnum.PAYMENT_ERROR,
                    "退款金额或币种与本地记录不一致");
        }

        OrderStripePayment orderStripePayment = orderStripePaymentMapper.selectPaymentByOrderId(orderId);
        if (orderStripePayment == null
                || StringUtils.isBlank(orderStripePayment.getPaymentIntentId())
                || !Objects.equals(orderStripePayment.getPaymentIntentId(), snapshot.paymentIntentId())) {
            throw new BusinessException(ResultCodeEnum.PAYMENT_ERROR, "退款关联的支付对象不匹配");
        }

        OrderStripeRefundAttempt latestAttempt =
                refundAttemptMapper.selectLatestAttemptByOrderIdForUpdate(orderId);
        boolean currentAttempt = latestAttempt != null && Objects.equals(latestAttempt.getId(), attemptId);
        RefundSyncResult refundSyncResult = syncStatus(orderStripeRefund, attempt, currentAttempt, order, snapshot);

        if (refundSyncResult == RefundSyncResult.CONFLICT) {
            log.warn("Stripe 退款状态冲突: orderId={}, previous={}, incoming={}",
                    orderId, attempt.getStripeStatus(), snapshot.status());
        }

        return refundSyncResult;

    }

    /** Attempt 状态只写回其自身；只有最新 Attempt 才能推进当前退款业务和订单。 */
    private RefundSyncResult syncStatus(OrderStripeRefund refund,
                                        OrderStripeRefundAttempt attempt,
                                        boolean currentAttempt,
                                        Order order,
                                        StripeRefundSnapshot snapshot) {
        String previousStatus = attempt.getStripeStatus();
        String incomingStatus = snapshot.status();
        String previousRefundId = attempt.getRefundId();

        if (StringUtils.isBlank(incomingStatus) || !REFUND_STATUSES.contains(incomingStatus)) {
            throw new IllegalStateException("不支持的退款状态: " + incomingStatus);
        }

        if (Objects.equals(previousStatus, incomingStatus) && previousRefundId != null) {
            boolean finishReconciliation = isTerminal(incomingStatus)
                    && ReconciliationState.isPending(attempt.getReconciliationState());
            if (!finishReconciliation && Objects.equals(attempt.getFailureReason(), snapshot.failureReason())) {
                return RefundSyncResult.UNCHANGED;
            }
            String nextState = finishReconciliation ? ReconciliationState.RECONCILED.code()
                    : attempt.getReconciliationState();
            updateAttemptStatus(attempt, snapshot, nextState);
            if (currentAttempt && isTerminal(incomingStatus) && !"succeeded".equals(incomingStatus)
                    && !allowsNewAttempt(incomingStatus, snapshot.failureReason())) {
                markRefundForReview(refund);
            }
            return RefundSyncResult.UPDATED;
        }

        if (isTerminal(previousStatus)) {
            if (!isTerminal(incomingStatus)) {
                // 在途请求的 pending 可能晚于已确认的终态到达。
                return RefundSyncResult.CONFLICT;
            }

            if (!currentAttempt) {
                updateAttemptStatus(attempt, snapshot, ReconciliationState.RECONCILED.code());
                return RefundSyncResult.UPDATED;
            }

            if ("succeeded".equals(previousStatus) && "failed".equals(incomingStatus)) {
                updateAttemptStatus(attempt, snapshot, ReconciliationState.REVIEW.code());
                updateRefundStatus(refund, RefundStatus.SUCCEEDED, RefundStatus.REVIEW);

                int orderAffected = orderMapper.updateOrderStatusToRefundingIfRefunded(
                        refund.getOrderId(),
                        OrderStatusEnum.REFUNDED.getCode(),
                        OrderStatusEnum.REFUNDING.getCode());
                if (orderAffected != 1) {
                    throw new IllegalStateException("退款失败后订单状态回退失败");
                }

                log.warn("Stripe 已成功退款后来报失败，退款业务转人工复核: orderId={}, attemptId={}, refundId={}",
                        refund.getOrderId(), attempt.getId(), snapshot.refundId());
                return RefundSyncResult.UPDATED;
            }

            markAttemptForReview(attempt);
            markRefundForReview(refund);
            return RefundSyncResult.CONFLICT;
        }

        if (previousStatus != null && !"pending".equals(previousStatus)) {
            throw new IllegalStateException("非法本地退款状态: " + previousStatus);
        }

        ReconciliationState nextReconciliationState = isTerminal(incomingStatus)
                ? ReconciliationState.RECONCILED
                : ReconciliationState.PENDING;
        updateAttemptStatus(attempt, snapshot, nextReconciliationState.code());

        if (!currentAttempt) {
            return RefundSyncResult.UPDATED;
        }

        if ("succeeded".equals(incomingStatus)) {
            finishRefund(refund, order);
            return RefundSyncResult.REFUNDED;
        }

        if (isTerminal(incomingStatus)) {
            log.warn("Stripe 退款 Attempt 未成功: orderId={}, attemptId={}, status={}, failureReason={}",
                    refund.getOrderId(), attempt.getId(), incomingStatus, snapshot.failureReason());

            if (!allowsNewAttempt(incomingStatus, snapshot.failureReason())) {
                markRefundForReview(refund);
            }
        }

        return RefundSyncResult.UPDATED;
    }

    private void updateAttemptStatus(OrderStripeRefundAttempt attempt,
                                     StripeRefundSnapshot snapshot,
                                     String reconciliationState) {
        int affected = refundAttemptMapper.updateAttemptStatus(
                attempt.getId(),
                attempt.getRefundId(),
                snapshot.refundId(),
                snapshot.status(),
                reconciliationState,
                snapshot.failureReason());
        if (affected != 1) {
            throw new IllegalStateException("退款 Attempt 状态更新失败");
        }
    }

    private void finishRefund(OrderStripeRefund refund, Order order) {
        if (!RefundStatus.SUCCEEDED.matches(refund.getStatus())) {
            updateRefundStatus(refund, RefundStatus.valueOf(refund.getStatus()), RefundStatus.SUCCEEDED);
        }

        if (Objects.equals(order.getStatus(), OrderStatusEnum.REFUNDING.getCode())) {
            int orderAffected = orderMapper.updateOrderStatusToRefunded(
                    refund.getOrderId(),
                    OrderStatusEnum.REFUNDING.getCode(),
                    OrderStatusEnum.REFUNDED.getCode());
            if (orderAffected != 1) {
                throw new IllegalStateException("退款成功后订单状态更新失败");
            }
        } else if (!Objects.equals(order.getStatus(), OrderStatusEnum.REFUNDED.getCode())) {
            throw new IllegalStateException("退款成功但订单不处于退款中");
        }
    }

    private void updateRefundStatus(OrderStripeRefund refund,
                                    RefundStatus expectedStatus,
                                    RefundStatus nextStatus) {
        if (orderStripeRefundMapper.updateRefundBusinessStatus(
                refund.getOrderId(), expectedStatus.code(), nextStatus.code()) != 1) {
            throw new IllegalStateException("退款业务状态更新失败");
        }
        refund.setStatus(nextStatus.code());
    }

    private OrderStripeRefundAttempt insertRefundAttempt(Long orderId) {
        OrderStripeRefundAttempt attempt = new OrderStripeRefundAttempt();
        attempt.setOrderId(orderId);
        attempt.setIdempotencyKey(PaymentPolicy.REFUND_ATTEMPT_KEY_PREFIX + UUID.randomUUID());
        attempt.setReconciliationState(ReconciliationState.PENDING.code());
        if (refundAttemptMapper.insert(attempt) != 1 || attempt.getId() == null) {
            throw new IllegalStateException("未能持久化退款 Attempt");
        }
        return attempt;
    }

    private boolean allowsNewAttempt(String stripeStatus, String failureReason) {
        return "failed".equals(stripeStatus) && refundRetryPolicy.allowsNewRefund(failureReason);
    }

    private void markRefundForReview(OrderStripeRefund refund) {
        if (RefundStatus.PENDING.matches(refund.getStatus())) {
            updateRefundStatus(refund, RefundStatus.PENDING, RefundStatus.REVIEW);
        }
    }

    private boolean isTerminal(String status) {
        return "succeeded".equals(status)
                || "failed".equals(status)
                || "canceled".equals(status);
    }

    private void markAttemptForReview(OrderStripeRefundAttempt attempt) {
        if (!ReconciliationState.REVIEW.matches(attempt.getReconciliationState())
                && attempt.getReconciliationState() != null) {
            refundAttemptMapper.updateAttemptReconciliationState(
                    attempt.getId(), attempt.getReconciliationState(), ReconciliationState.REVIEW.code());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void scheduleConflictRetry(Long orderId, Long attemptId) {
        Order order = orderMapper.selectOrderForUpdate(orderId);
        OrderStripeRefund refund = orderStripeRefundMapper.selectByOrderIdForUpdate(orderId);
        OrderStripeRefundAttempt attempt = refundAttemptMapper.selectAttemptByIdForUpdate(attemptId);
        if (order == null || refund == null || attempt == null
                || !Objects.equals(orderId, attempt.getOrderId())
                || !ReconciliationState.isPending(attempt.getReconciliationState())) {
            return;
        }

        LocalDateTime now = orderStripePaymentMapper.selectDatabaseTime();
        if (attempt.getNextRetryAt() != null && attempt.getNextRetryAt().isAfter(now)) {
            return;
        }

        int retryCount = attempt.getRetryCount() == null ? 0 : attempt.getRetryCount();
        if (retryCount < PaymentPolicy.MAX_RECOVERY_ATTEMPTS) {
            retryCount++;
        }

        boolean needsReview = retryCount >= PaymentPolicy.MAX_RECOVERY_ATTEMPTS;
        ReconciliationState nextState = needsReview
                ? ReconciliationState.REVIEW
                : ReconciliationState.PENDING;
        LocalDateTime nextRetryAt = needsReview
                ? null
                : now.plusSeconds(PaymentPolicy.retryDelaySeconds(retryCount));

        if (refundAttemptMapper.updateAttemptRetrySchedule(
                attemptId,
                ReconciliationState.PENDING.code(),
                nextState.code(),
                retryCount,
                nextRetryAt) != 1) {
            throw new IllegalStateException("退款 Attempt 对账重试调度失败");
        }

        OrderStripeRefundAttempt latest = refundAttemptMapper.selectLatestAttemptByOrderIdForUpdate(orderId);
        boolean currentAttempt = latest != null && Objects.equals(latest.getId(), attemptId);
        if (needsReview && currentAttempt && RefundStatus.PENDING.matches(refund.getStatus())) {
            markRefundForReview(refund);
        }
        if (needsReview) {
            log.warn("Stripe 退款 Attempt 对账转人工复核: orderId={}, attemptId={}, retryCount={}",
                    orderId, attemptId, retryCount);
        }
    }
}
