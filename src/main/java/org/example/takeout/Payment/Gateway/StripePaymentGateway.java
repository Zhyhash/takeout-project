package org.example.takeout.Payment.Gateway;

/** Small, testable boundary around Stripe SDK calls. */
public interface StripePaymentGateway {
    StripePaymentSnapshot create(long amountMinor, String orderReference, String idempotencyKey);

    StripePaymentSnapshot retrieve(String paymentIntentId);

    StripePaymentEvent verifyEvent(String rawBody, String signature);
}
