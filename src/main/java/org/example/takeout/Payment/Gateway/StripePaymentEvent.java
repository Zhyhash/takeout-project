package org.example.takeout.Payment.Gateway;

/** A signature-verified Stripe snapshot event. Non-PaymentIntent events have a null payment. */
public record StripePaymentEvent(
        String id,
        String type,
        boolean livemode,
        StripePaymentSnapshot payment
) {
}
