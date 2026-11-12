package org.example.takeout.Payment.Service;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.RefundSyncResult;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeRefundTransactionService {
    private static final Set<String> REFUND_STATUSES =
            Set.of("pending", "succeeded", "failed", "canceled");

    private final OrderStripeRefundMapper orderStripeRefundMapper;

    private final OrderMapper orderMapper;
    private final OrderStripePaymentMapper orderStripePaymentMapper;
    private final OrderItemService orderItemService;

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
        // 幂等键：同一订单的退款操作重试时复用，保证 Stripe 侧幂等
        orderStripeRefund.setIdempotencyKey("order_refund_" + orderId);
        orderStripeRefund.setReconciliationState(ReconciliationState.PENDING.code());
        orderStripeRefundMapper.insert(orderStripeRefund);
        return new RefundPreparation(orderStripeRefund,true);
    }

    /**
     * 用 Stripe 的退款快照推进本地退款记录与订单状态。
     * <p>锁顺序与支付保持一致：先 orders，再 order_stripe_refunds，避免与支付链路交叉死锁。</p>
     * <p>首次同步时本地还没有 {@code refundId}，此时额外核对 payment 表，确认这笔退款确实属于
     * 该订单的 PaymentIntent，防止把别的退款对象关联到本订单。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundSyncResult syncRefundStatus(Long orderId,
            StripeRefundSnapshot snapshot){
        if (orderId == null || snapshot == null) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR,
                    "订单 ID 与退款快照不能为空");
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

        if (orderStripeRefund.getRefundId() != null
                && !orderStripeRefund.getRefundId().equals(snapshot.refundId())) {
            throw new IllegalStateException("退款对象不匹配");
        }

        // 金额与币种是退款事实的一部分，任何一次同步都必须与本地发起时一致
        if (!Objects.equals(orderStripeRefund.getAmountMinor(), snapshot.amountMinor())
                || !Objects.equals(orderStripeRefund.getCurrency(), snapshot.currency())) {
            throw new BusinessException(ResultCodeEnum.PAYMENT_ERROR,
                    "退款金额或币种与本地记录不一致");
        }

        //在第一次绑定的时候，RefundId == null是正常的，需要额外查询一次payment表
        if (orderStripeRefund.getRefundId() == null){
            OrderStripePayment orderStripePayment = orderStripePaymentMapper.selectPaymentByOrderId(orderId);

            if (orderStripePayment == null
                    || orderStripePayment.getPaymentIntentId() == null
                    || !Objects.equals(
                    orderStripePayment.getPaymentIntentId(),
                    snapshot.paymentIntentId())) {
                throw new BusinessException(
                        ResultCodeEnum.PAYMENT_ERROR,
                        "退款关联的支付对象不匹配"
                );
            }

        }
        //状态同步
        RefundSyncResult refundSyncResult = syncStatus(orderStripeRefund, snapshot);

        if (refundSyncResult == RefundSyncResult.CONFLICT) {
            log.warn("Stripe 退款状态冲突: orderId={}, previous={}, incoming={}",
                    orderId, orderStripeRefund.getStripeStatus(), snapshot.status());
        }

        return refundSyncResult;

    }

    /**
     * 退款状态机：本地只跑一次 {@code null/pending -> 终态}，终态不再回退。
     * <p>状态相同不代表无事可做：首次同步时本地还没有 {@code refundId}，如果远端同样处于
     * {@code pending}，只比状态就会提前返回，退款对象永远绑不上。因此幂等短路的条件是
     * "状态一致 <b>且</b> 已绑定"，未绑定的一律继续走下面的写入路径完成绑定。</p>
     * <p>已绑定的终态回查一致时，结束仍在进行的本地对账重试。</p>
     * <p>{@code succeeded} 会同时把订单从 {@code REFUNDING} 推到 {@code REFUNDED}，并让调用方归还库存；
     * {@code failed}/{@code canceled} 表示钱没退出去，订单不能假装已退款，只把对账状态转人工。</p>
     */
    private RefundSyncResult syncStatus(
            OrderStripeRefund refund,
            StripeRefundSnapshot snapshot
    ) {
        String oldStatus = refund.getStripeStatus();
        String newStatus = snapshot.status();

        if (StringUtils.isBlank(newStatus) || !REFUND_STATUSES.contains(newStatus)) {
            throw new IllegalStateException("不支持的退款状态: " + newStatus);
        }

        // 首次同步：退款单号尚未落库
        boolean needsBind = refund.getRefundId() == null;

        // 回查确认已绑定的本地终态后，结束对账重试。
        if (Objects.equals(oldStatus, newStatus) && !needsBind) {
            if (isTerminal(oldStatus)
                    && ReconciliationState.isPending(
                    refund.getReconciliationState())) {

                if (orderStripeRefundMapper.updateReconciliationState(
                        refund.getOrderId(),
                        ReconciliationState.RECONCILED.code()) != 1) {
                    throw new IllegalStateException("退款对账状态更新失败");
                }

                return RefundSyncResult.UPDATED;
            }

            return RefundSyncResult.UNCHANGED;
        }

        // 终态不允许覆盖；本地已是终态却还缺 refundId 属于异常数据，同样只能转人工
        if (isTerminal(oldStatus)) {
            return RefundSyncResult.CONFLICT;
        }

        // 只允许首次同步或 pending 转换
        if (oldStatus != null && !"pending".equals(oldStatus)) {
            throw new IllegalStateException("非法本地退款状态: " + oldStatus);
        }

        if (needsBind) {
            log.info("Stripe 退款单号首次绑定: orderId={}, refundId={}, localStatus={}, remoteStatus={}",
                    refund.getOrderId(), snapshot.refundId(), oldStatus, newStatus);
        }

        if ("succeeded".equals(newStatus)) {
            int refundAffected =
                    orderStripeRefundMapper.updateStatus(
                            refund.getOrderId(), snapshot.refundId(), newStatus,
                            ReconciliationState.RECONCILED.code());

            int orderAffected =
                    orderMapper.updateOrderStatusToRefunded(
                            refund.getOrderId(),
                            OrderStatusEnum.REFUNDING.getCode(),
                            OrderStatusEnum.REFUNDED.getCode());

            if (refundAffected != 1 || orderAffected != 1) {
                throw new IllegalStateException("退款状态更新失败");
            }

            return RefundSyncResult.REFUNDED;
        }

        // pending 继续等；failed/canceled 是"钱没退成"的终态，需要人工决定重试还是回滚。
        // 首次同步落到这里时，这一步同时完成 refundId 绑定。
        ReconciliationState nextState = isTerminal(newStatus)
                ? ReconciliationState.RECONCILED
                : ReconciliationState.PENDING;

        int affected = orderStripeRefundMapper.updateStatus(
                refund.getOrderId(), snapshot.refundId(), newStatus, nextState.code());

        if (affected != 1) {
            throw new IllegalStateException("退款记录更新失败");
        }

        if (isTerminal(newStatus)) {
            log.warn("Stripe 退款未成功: orderId={}, status={}",
                    refund.getOrderId(), newStatus);
        }

        // pending / failed / canceled
        return RefundSyncResult.UPDATED;
    }

    private boolean isTerminal(String status) {
        return "succeeded".equals(status)
                || "failed".equals(status)
                || "canceled".equals(status);
    }

    @Transactional
    public void scheduleConflictRetry(Long orderId) {
        OrderStripeRefund refund =
                orderStripeRefundMapper.selectByOrderIdForUpdate(orderId);

        if (refund == null) {
            throw new IllegalStateException("退款记录不存在");
        }

        // 调度以加锁后的状态为准，旧快照或恢复异常可能晚于另一条同步完成。
        if (!ReconciliationState.isPending(refund.getReconciliationState())) {
            return;
        }

        // 以恢复查询使用的数据库时钟为准，同一退避窗口内只安排一次。
        LocalDateTime now = orderStripePaymentMapper.selectDatabaseTime();
        if (refund.getNextRetryAt() != null && refund.getNextRetryAt().isAfter(now)) {
            return;
        }

        int retryCount = refund.getRetryCount() == null ? 0 : refund.getRetryCount();
        if (retryCount < PaymentPolicy.MAX_RECOVERY_ATTEMPTS) {
            retryCount++;
        }

        boolean needsReview = retryCount >= PaymentPolicy.MAX_RECOVERY_ATTEMPTS
                || (isTerminal(refund.getStripeStatus()) && StringUtils.isBlank(refund.getRefundId()));

        ReconciliationState nextState;
        LocalDateTime nextRetryAt;

        if (needsReview) {
            nextState = ReconciliationState.REVIEW;
            nextRetryAt = null;
        } else {
            nextState = ReconciliationState.PENDING;
            nextRetryAt = now.plusSeconds(
                    PaymentPolicy.retryDelaySeconds(retryCount));
        }

        if (orderStripeRefundMapper.updateRetrySchedule(orderId, nextState.code(), retryCount, nextRetryAt) != 1) {
            throw new IllegalStateException("退款对账重试调度失败");
        }
        if (needsReview) {
            log.warn("Stripe 退款对账转人工复核: orderId={}, retryCount={}", orderId, retryCount);
        }
    }
}
