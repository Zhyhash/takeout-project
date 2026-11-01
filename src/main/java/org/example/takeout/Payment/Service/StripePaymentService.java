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

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class StripePaymentService {
    private static final Set<String> EVENTS = Set.of("payment_intent.succeeded",
            "payment_intent.payment_failed", "payment_intent.canceled");
    private final StripePaymentTransactionService transactions;
    private final StripePaymentGateway gateway;
    private final StripePaymentProperties properties;

    public StripePaymentView create(Long orderId) {
        Long userId = UserContextHolder.getUserId();
        Order order = transactions.requireUserOrder(orderId, userId);
        StripePaymentTransactionService.requirePayable(order);
        OrderStripePayment payment = transactions.prepare(orderId, userId, toCnyMinor(order.getTotalAmount()));
        StripePaymentSnapshot snapshot;
        if (payment.getPaymentIntentId() == null) {
            snapshot = gateway.create(payment.getAmountMinor(), orderId.toString(), payment.getIdempotencyKey());
            payment = transactions.attach(orderId, snapshot);
        } else {
            snapshot = gateway.retrieve(payment.getPaymentIntentId());
            payment = transactions.apply(snapshot, null, null);
        }
        return view(payment, transactions.requireUserOrder(orderId, userId), snapshot.clientSecret());
    }

    public StripePaymentView sync(Long orderId) {
        Long userId = UserContextHolder.getUserId();
        OrderStripePayment payment = transactions.findForUser(orderId, userId);
        if (payment == null || payment.getPaymentIntentId() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单尚未创建 Stripe 支付请求");
        }
        payment = transactions.apply(gateway.retrieve(payment.getPaymentIntentId()), null, null);
        return view(payment, transactions.requireUserOrder(orderId, userId), null);
    }

    public StripePaymentView get(Long orderId) {
        Long userId = UserContextHolder.getUserId();
        OrderStripePayment payment = transactions.findForUser(orderId, userId);
        return view(payment, transactions.requireUserOrder(orderId, userId), null);
    }

    public void handleWebhook(String rawBody, String signature) {
        StripePaymentEvent event = gateway.verifyEvent(rawBody, signature);
        if (event.livemode()) throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "拒绝真实环境支付事件");
        if (event.type() != null && EVENTS.contains(event.type()) && event.payment() != null) {
            transactions.apply(event.payment(), event.id(), event.type());
        }
    }

    /** CNY yuan to fen, with no rounding and no foreign-exchange conversion. */
    public static long toCnyMinor(BigDecimal amount) {
        try {
            long minor = amount.movePointRight(2).longValueExact();
            if (minor <= 0 || minor > 99_999_999L) throw new ArithmeticException();
            return minor;
        } catch (ArithmeticException | NullPointerException invalid) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "订单人民币金额必须为正数、精确到分且符合 Stripe 金额上限");
        }
    }

    private StripePaymentView view(OrderStripePayment payment, Order order, String clientSecret) {
        boolean terminal = payment != null && ("succeeded".equals(payment.getStripeStatus())
                || "canceled".equals(payment.getStripeStatus()));
        boolean payable = Integer.valueOf(0).equals(order.getStatus()) && order.getCreateTime() != null
                && order.getCreateTime().isAfter(LocalDateTime.now().minusMinutes(30));
        return new StripePaymentView(order.getId(), payment == null ? null : payment.getPaymentIntentId(),
                terminal || !payable ? null : clientSecret, properties.getPublishableKey(),
                payment == null ? null : payment.getAmountMinor(), "cny",
                payment == null ? null : payment.getStripeStatus(), order.getStatus());
    }
}
