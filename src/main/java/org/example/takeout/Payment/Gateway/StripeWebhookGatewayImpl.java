package org.example.takeout.Payment.Gateway;

import com.stripe.StripeClient;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;

import java.util.Map;

/**
 * 将 Stripe Webhook 事件适配为本地快照。
 * <p>签名、事件 ID、环境标识或业务对象任一不可信时，统一抛出 {@code INVALID_WEBHOOK}，
 * 由上层决定重试或丢弃，避免拿不确定的载荷改动本地支付状态。</p>
 */
public final class StripeWebhookGatewayImpl implements StripeWebhookGateway {
    private static final String ORDER_REFERENCE_METADATA_KEY = "order_reference";

    private final StripeClient stripeClient;
    private final String webhookSecret;

    public StripeWebhookGatewayImpl(StripeClient stripeClient, String webhookSecret) {
        this.stripeClient = stripeClient;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public StripeWebhookEvent verifySignatureAndParseEvent(String rawBody, String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        final Event event;
        try {
            event = stripeClient.constructEvent(rawBody, signature, webhookSecret);
        } catch (Exception exception) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        Boolean eventLivemode = event.getLivemode();
        if (event.getId() == null || event.getType() == null || eventLivemode == null) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        StripePaymentSnapshot payment = null;
        StripeRefundSnapshot refund = null;
        if (event.getType().startsWith("payment_intent.")) {
            requireApiVersion(event);
            StripeObject stripeObject = deserializeEventObject(event);
            if (stripeObject instanceof PaymentIntent intent) {
                payment = mapPaymentToSnapshot(intent);
            } else {
                throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
            }
        } else if (event.getType().startsWith("refund.")) {
            requireApiVersion(event);
            StripeObject stripeObject = deserializeEventObject(event);
            if (stripeObject instanceof Refund refundObject) {
                String creationKey = "refund.created".equals(event.getType()) && event.getRequest() != null
                        ? event.getRequest().getIdempotencyKey() : null;
                refund = mapRefundToSnapshot(refundObject, creationKey);
            } else {
                throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
            }
        }
        return new StripeWebhookEvent(event.getId(), event.getType(), eventLivemode, payment, refund);
    }

    /** 事件版本缺失时无法保证载荷字段含义，按不可信处理。 */
    private void requireApiVersion(Event event) {
        String eventApiVersion = event.getApiVersion();
        if (eventApiVersion == null || eventApiVersion.isBlank()) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }
    }

    /**
     * 取事件里的业务对象。SDK 版本不匹配时 {@code getObject()} 为空，
     * 此时退化为宽松反序列化，再由调用方校验对象类型。
     */
    private StripeObject deserializeEventObject(Event event) {
        try {
            var deserializer = event.getDataObjectDeserializer();
            if (deserializer == null) {
                throw new IllegalStateException("Stripe event data is missing");
            }

            StripeObject deserializedObject = deserializer.getObject().orElse(null);
            return deserializedObject != null ? deserializedObject : deserializer.deserializeUnsafe();
        } catch (com.stripe.exception.EventDataObjectDeserializationException | RuntimeException exception) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }
    }

    private StripePaymentSnapshot mapPaymentToSnapshot(PaymentIntent intent) {
        if (intent == null || intent.getLivemode() == null) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        Map<String, String> metadata = intent.getMetadata();

        // Webhook 事件永远不会下发给客户端，因此一律不带客户端密钥。
        return new StripePaymentSnapshot(
                intent.getId(),
                intent.getAmount(),
                intent.getAmountReceived(),
                intent.getCurrency(),
                intent.getStatus(),
                intent.getLivemode(),
                metadata == null ? null : metadata.get(ORDER_REFERENCE_METADATA_KEY),
                null
        );
    }

    private StripeRefundSnapshot mapRefundToSnapshot(Refund refund, String creationKey) {
        // 退款事件的必填字段缺失说明载荷不可信，同一个事件 ID 不能据此落库。
        if (refund == null
                || refund.getId() == null || refund.getId().isBlank()
                || refund.getAmount() == null
                || refund.getCurrency() == null
                || refund.getStatus() == null) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        // 迁移前的 Refund 没有 metadata，已验签的创建事件有时仍携带原始请求的幂等键。
        String attemptKey = refund.getMetadata() == null ? null : refund.getMetadata().get(StripeRefundSnapshot.ATTEMPT_METADATA_KEY);
        return new StripeRefundSnapshot(
                refund.getId(),
                refund.getPaymentIntent(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getFailureReason(),
                attemptKey != null ? attemptKey : creationKey
        );
    }
}
