package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Payment.Config.StripePaymentProperties;
import org.example.takeout.Payment.Entity.OrderStripePayment;
import org.example.takeout.Payment.Gateway.StripePaymentEvent;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
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
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        StripePaymentTransactionService.validateOrderCanBePaid(order);
        OrderStripePayment payment = paymentTransactions.getOrCreateOrderPaymentRecord(
                orderId, userId, convertCnyYuanToFen(order.getTotalAmount()));
        StripePaymentSnapshot snapshot;
        if (payment.getPaymentIntentId() == null) {
            snapshot = paymentGateway.createPaymentIntent(
                    payment.getAmountMinor(), orderId.toString(), payment.getIdempotencyKey());
            payment = paymentTransactions.bindPaymentIntentToOrder(orderId, snapshot);
        } else {
            snapshot = paymentGateway.retrievePaymentIntent(payment.getPaymentIntentId());
            payment = paymentTransactions.syncPaymentStatusFromSnapshot(snapshot);
        }
        return buildOrderPaymentView(
                payment, paymentTransactions.requireOrderOwnedByUser(orderId, userId), snapshot.clientSecret());
    }

    /**
     * 主动查询订单已关联的 Stripe 支付意图，并将结果同步到本地。
     * <p>支付成功时尝试确认订单；已经取消或超过支付期限的订单不会因此恢复为已支付。</p>
     *
     * @param orderId 当前用户的订单 ID
     * @return 同步后的订单支付视图，不包含客户端密钥
     * @throws BusinessException 订单不属于当前用户、尚未关联支付意图或支付结果与订单不符
     */
    public StripePaymentView syncOrderPaymentStatusFromStripe(Long orderId) {
        Long userId = UserContextHolder.getUserId();
        OrderStripePayment payment = paymentTransactions.findOrderPaymentForUser(orderId, userId);
        if (payment == null || payment.getPaymentIntentId() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单尚未创建 Stripe 支付请求");
        }
        payment = paymentTransactions.syncPaymentStatusFromSnapshot(
                paymentGateway.retrievePaymentIntent(payment.getPaymentIntentId()));
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
            paymentTransactions.recordWebhookPaymentEvent(event);
        }
    }

    /**
     * 将人民币金额从元精确转换为分，供 Stripe 支付请求使用，不进行舍入或汇率换算。
     *
     * @param amount 以人民币元为单位的订单金额
     * @return 以分为单位的正整数金额
     * @throws BusinessException 金额为空、非正数、无法精确到分或超过允许的支付金额上限
     */
    public static long convertCnyYuanToFen(BigDecimal amount) {
        try {
            long minor = amount.movePointRight(2).longValueExact();
            if (minor <= 0 || minor > 99_999_999L) throw new ArithmeticException();
            return minor;
        } catch (ArithmeticException | NullPointerException invalid) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "订单人民币金额必须为正数、精确到分且符合 Stripe 金额上限");
        }
    }

    private StripePaymentView buildOrderPaymentView(OrderStripePayment payment, Order order, String clientSecret) {
        boolean terminal = payment != null && ("succeeded".equals(payment.getStripeStatus())
                || "canceled".equals(payment.getStripeStatus()));
        boolean payable = Integer.valueOf(0).equals(order.getStatus()) && order.getCreateTime() != null
                && order.getCreateTime().isAfter(LocalDateTime.now().minusMinutes(30));
        return new StripePaymentView(order.getId(), payment == null ? null : payment.getPaymentIntentId(),
                terminal || !payable ? null : clientSecret, paymentProperties.getPublishableKey(),
                payment == null ? null : payment.getAmountMinor(), "cny",
                payment == null ? null : payment.getStripeStatus(), order.getStatus());
    }
}
