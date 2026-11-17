package org.example.takeout.Payment.Gateway;

/** 将 Stripe 支付意图操作封装为可替换的业务边界。Webhook 解析见 {@link StripeWebhookGateway}。 */
public interface StripePaymentGateway {
    StripePaymentSnapshot createPaymentIntent(long amountMinor, String orderReference, String idempotencyKey);

    StripePaymentSnapshot retrievePaymentIntent(String paymentIntentId);

    StripePaymentSnapshot cancelPaymentIntent(String paymentIntentId, String idempotencyKey);
}
