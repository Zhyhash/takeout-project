package org.example.takeout.Payment.Gateway;

/** 校验 Stripe Webhook 签名，并把事件载荷解析为本地支付或退款快照。 */
public interface StripeWebhookGateway {
    StripeWebhookEvent verifySignatureAndParseEvent(String rawBody, String signature);
}
