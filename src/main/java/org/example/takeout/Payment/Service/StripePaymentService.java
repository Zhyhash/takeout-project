package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Payment.Config.StripePaymentProperties;
import org.example.takeout.Payment.Domain.Money;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.StripeStatus;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentEvent;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.example.takeout.Payment.Gateway.StripeGatewayException;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.VO.StripePaymentView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/** 编排订单支付：在数据库事务之外调用 Stripe，再交给本地短事务保存关联或支付结果。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class StripePaymentService {
    private static final Set<String> SUPPORTED_PAYMENT_EVENT_TYPES = Set.of("payment_intent.succeeded",
            "payment_intent.payment_failed", "payment_intent.canceled");

    private final StripePaymentTransactionService paymentTransactions;
    private final StripePaymentGateway paymentGateway;
    private final StripePaymentProperties paymentProperties;

    /**
     * 为当前用户的待支付订单创建或复用 Stripe 支付意图。
     * <p>复用本地保存的幂等键和已有 PaymentIntent；Stripe 请求在数据库事务之外执行。
     * 仅在订单仍可支付且支付未结束时，向前端提供客户端确认信息。</p>
     *
     * @param orderId 当前用户的订单 ID
     * @return 订单和支付状态，以及按支付条件筛选的客户端确认信息
     * @throws BusinessException 订单不属于当前用户、不可支付或支付金额不符合要求
     */
    public StripePaymentView createOrReuseOrderPaymentIntent(Long orderId) {
        Long userId = UserContextHolder.getUserId();
        Order order = paymentTransactions.requireOrderOwnedByUser(orderId, userId);
        // 构造即校验：不必等到写入支付记录后才发现金额不合法。
        Money.ofYuan(order.getTotalAmount());
        paymentTransactions.beginOrderPayment(orderId, userId);

        String clientSecret = recoverOrderPayment(orderId);

        return buildOrderPaymentView(paymentTransactions.findOrderPaymentForUser(orderId, userId),
                paymentTransactions.requireOrderOwnedByUser(orderId, userId), clientSecret);
    }

    /**
     * 主动查询订单已关联的 Stripe 支付意图，并将结果同步到本地。
     * <p>与后台恢复共享租约和退避；已取得支付资格的订单按可信终态确认，不再用本地期限拒绝成功结果。</p>
     *
     * @param orderId 当前用户的订单 ID
     * @return 同步后的订单支付视图，不包含客户端密钥
     * @throws BusinessException 订单不属于当前用户、尚未关联支付意图或支付结果与订单不符
     */
    public StripePaymentView syncOrderPaymentStatusFromStripe(Long orderId) {
        Long userId = UserContextHolder.getUserId();
        OrderStripePayment payment = paymentTransactions.findOrderPaymentForUser(orderId, userId);
        if (payment == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单尚未创建 Stripe 支付请求");
        }

        recoverOrderPayment(orderId);
        payment = paymentTransactions.findOrderPaymentForUser(orderId, userId);

        return buildOrderPaymentView(payment, paymentTransactions.requireOrderOwnedByUser(orderId, userId), null);
    }

    /**
     * 读取当前用户订单在本地保存的支付状态，不请求 Stripe，也不推进订单状态。
     *
     * @param orderId 当前用户的订单 ID
     * @return 不包含客户端密钥的本地订单支付视图；尚无支付记录时，关联支付字段可为空
     * @throws BusinessException 订单不存在或不属于当前用户
     */
    public StripePaymentView getLocalOrderPaymentStatus(Long orderId) {
        Long userId = UserContextHolder.getUserId();
        OrderStripePayment payment = paymentTransactions.findOrderPaymentForUser(orderId, userId);

        return buildOrderPaymentView(payment, paymentTransactions.requireOrderOwnedByUser(orderId, userId), null);
    }

    /**
     * 执行一次有界的支付恢复尝试：占位 → 取远端真相 → 落本地 → 释放。
     * <p>所有网关 HTTP 调用都发生在已提交的短事务之间，没有数据库事务跨越网络请求。
     * 占位由 {@code next_retry_at} 前推完成，因此失败后无需额外收尾，等待下一个退避周期即可。</p>
     *
     * @return 远端支付意图的客户端密钥；未能占位（已完结或已转人工）时返回 null
     */
    public String recoverOrderPayment(Long orderId) {
        OrderStripePayment attempt = paymentTransactions.claimPaymentAttempt(orderId);
        if (attempt == null) {
            return null;
        }

        StripePaymentSnapshot snapshot;
        if (attempt.getPaymentIntentId() == null) {
            // 占位后线程可能被挂起，创建前再核对一次：仍是待处理、未绑定、且幂等窗口足够。
            if (!paymentTransactions.canCreatePaymentIntent(attempt)) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "创建结果未知且恢复窗口不足，等待核实");
            }
            snapshot = paymentGateway.createPaymentIntent(attempt.getAmountMinor(), orderId.toString(),
                    attempt.getIdempotencyKey());
            paymentTransactions.bindPaymentIntentToOrder(orderId, snapshot);
        } else {
            snapshot = paymentGateway.retrievePaymentIntent(attempt.getPaymentIntentId());
            paymentTransactions.syncPaymentStatusFromSnapshot(snapshot);
        }

        Order order = paymentTransactions.findOrderForRecovery(orderId);
        // 远端仍在处理中、且订单已过支付窗口时，主动取消这笔 PaymentIntent。
        // 用精确的 matches 而非 parseOrNull，保证未知状态被保守地"不取消"，与既有行为一致。
        if (!StripeStatus.SUCCEEDED.matches(snapshot.status())
                && !StripeStatus.CANCELED.matches(snapshot.status())
                && PaymentPolicy.isPaymentWindowExpired(order.getCreateTime(), LocalDateTime.now())) {
            snapshot = cancelExpiredPaymentIntent(attempt, snapshot);
            paymentTransactions.syncPaymentStatusFromSnapshot(snapshot);
        }
        return snapshot.clientSecret();
    }

    /**
     * 取消已过支付窗口的 PaymentIntent，并按真实状态收场。
     * <p>Stripe 可能拒绝取消，最常见的原因是用户恰好在同一刻完成了支付。这类拒绝是**确定的业务结论**
     * ——远端已经不是"进行中"了，用同样的参数重试不会有不同结果，所以改为重新拉取真实快照。
     * 而"没有拿到答复"（超时、5xx）时远端状态未知，必须原样抛出交给下一轮恢复核实。</p>
     *
     * @return 取消成功后的快照，或取消被拒后重新拉取到的真实快照
     */
    private StripePaymentSnapshot cancelExpiredPaymentIntent(OrderStripePayment attempt, StripePaymentSnapshot snapshot) {
        try {
            return paymentGateway.cancelPaymentIntent(snapshot.id(), "cancel-" + attempt.getIdempotencyKey());
        } catch (StripeGatewayException failure) {
            if (failure.getKind() != StripeGatewayException.Kind.REQUEST_REJECTED) {
                throw failure;
            }
            log.warn("Stripe 拒绝取消，改为按远端真实状态收场: orderId={}, intentId={}, httpStatus={}, code={}",
                    attempt.getOrderId(), snapshot.id(), failure.getHttpStatus(), failure.getStripeErrorCode());
            return paymentGateway.retrievePaymentIntent(snapshot.id());
        }
    }

    /**
     * 验证 Webhook 签名，并处理受支持的测试环境支付事件。
     * <p>拒绝真实环境事件；忽略不支持的事件类型或不含支付对象的事件。
     * 有效支付事件交由本地短事务完成去重、状态保存及订单支付确认。</p>
     *
     * @param rawBody 未经改写的 Webhook 原始请求体，用于签名验证
     * @param signature 请求头 Stripe-Signature 的值
     * @throws BusinessException 收到真实环境事件或支付结果与本地订单不符
     */
    public void verifyAndProcessWebhookEvent(String rawBody, String signature) {
        StripePaymentEvent event = paymentGateway.verifySignatureAndParsePaymentEvent(rawBody, signature);
        if (event.livemode()) throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "拒绝真实环境支付事件");

        if (event.type() != null && SUPPORTED_PAYMENT_EVENT_TYPES.contains(event.type())
                && event.payment() != null) {
            try {
                paymentTransactions.recordWebhookPaymentEvent(event);
            } catch (RuntimeException failure) {
                try {
                    paymentTransactions.recordWebhookFailure(event.payment());
                } catch (RuntimeException persistenceFailure) {
                    failure.addSuppressed(persistenceFailure);
                }
                throw failure;
            }
        }
    }

    /**
     * 将人民币金额从元精确转换为分，供 Stripe 支付请求使用，不进行舍入或汇率换算。
     * <p>规则实现已迁移到 {@link Money}，此处仅保留既有调用入口，避免一次性改动过多调用点。</p>
     *
     * @param amount 以人民币元为单位的订单金额
     * @return 以分为单位的正整数金额
     * @throws BusinessException 金额为空、非正数、无法精确到分或超过允许的支付金额上限
     */
    public static long convertCnyYuanToFen(BigDecimal amount) {
        return Money.ofYuan(amount).minorUnits();
    }

    private StripePaymentView buildOrderPaymentView(OrderStripePayment payment, Order order, String clientSecret) {
        boolean terminal = payment != null
                && (StripeStatus.SUCCEEDED.matches(payment.getStripeStatus())
                || StripeStatus.CANCELED.matches(payment.getStripeStatus()));
        boolean payable = OrderStatusEnum.PAYING.getCode().equals(order.getStatus())
                && PaymentPolicy.isWithinPaymentWindow(order.getCreateTime(), LocalDateTime.now());
        // 人工复核中的支付不再下发客户端密钥，避免用户在前端继续确认一笔已存疑的支付。
        boolean underReview = payment != null
                && ReconciliationState.REVIEW.matches(payment.getReconciliationState());
        String exposedSecret = terminal || !payable || underReview ? null : clientSecret;

        return new StripePaymentView(order.getId(), payment == null ? null : payment.getPaymentIntentId(),
                exposedSecret, paymentProperties.getPublishableKey(),
                payment == null ? null : payment.getAmountMinor(), Money.CURRENCY,
                payment == null ? null : payment.getStripeStatus(), order.getStatus());
    }
}
