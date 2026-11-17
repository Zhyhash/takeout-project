package org.example.takeout.Payment.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Payment.Gateway.StripeWebhookEvent;
import org.example.takeout.Payment.Gateway.StripeWebhookGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Stripe Webhook 的编排入口：验签解析、环境裁决、按类型分发。
 * <p>这里不开启事务：{@code NEVER} 保证没有外层事务把「接收 + 远端回查 + 本地落库」包成一个大事务，
 * 每条事件的下游各自开启短事务，网络调用始终留在事务之外。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class StripeWebhookService {
    private static final Set<String> SUPPORTED_PAYMENT_EVENT_TYPES = Set.of("payment_intent.succeeded",
            "payment_intent.payment_failed", "payment_intent.canceled");
    private static final Set<String> SUPPORTED_REFUND_EVENT_TYPES = Set.of("refund.created",
            "refund.updated", "refund.failed");

    private final StripeWebhookGateway webhookGateway;
    private final StripePaymentService paymentService;
    private final StripeRefundService refundService;

    /**
     * 处理一次 Webhook 投递。
     *
     * @param rawBody 未经改写的原始请求体，用于签名验证
     * @param signature 请求头 Stripe-Signature 的值
     * @throws BusinessException 收到真实环境事件，或事件内容与本地订单不符
     */
    public void processWebhookEvent(String rawBody, String signature) {
        StripeWebhookEvent event = webhookGateway.verifySignatureAndParseEvent(rawBody, signature);
        validateWebhookEnvironment(event);
        dispatchEvent(event);
    }

    /** 只处理测试环境事件：真实环境事件一律拒绝，避免误改生产数据。 */
    private void validateWebhookEnvironment(StripeWebhookEvent event) {
        if (event.livemode()) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "拒绝真实环境支付事件");
        }
    }

    /** 按事件类型分发；不支持的类型或不含业务对象的事件直接忽略。 */
    private void dispatchEvent(StripeWebhookEvent event) {
        if (SUPPORTED_PAYMENT_EVENT_TYPES.contains(event.type()) && event.payment() != null) {
            paymentService.processWebhookPaymentEvent(event);
            return;
        }
        if (SUPPORTED_REFUND_EVENT_TYPES.contains(event.type()) && event.refund() != null) {
            refundService.processWebhookRefundEvent(event);
            return;
        }
        log.info("忽略不支持的 Stripe 事件: eventId={}, type={}", event.id(), event.type());
    }
}
