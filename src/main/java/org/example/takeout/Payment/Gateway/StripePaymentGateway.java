package org.example.takeout.Payment.Gateway;

/** 将 Stripe 支付意图操作与 Webhook 签名验证、事件解析封装为可替换的业务边界。 */
public interface StripePaymentGateway {
    StripePaymentSnapshot createPaymentIntent(long amountMinor, String orderReference, String idempotencyKey);

    StripePaymentSnapshot retrievePaymentIntent(String paymentIntentId);

    StripePaymentSnapshot cancelPaymentIntent(String paymentIntentId, String idempotencyKey);

    StripePaymentEvent verifySignatureAndParsePaymentEvent(String rawBody, String signature);
}
