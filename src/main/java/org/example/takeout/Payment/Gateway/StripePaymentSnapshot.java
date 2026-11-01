package org.example.takeout.Payment.Gateway;

/** A minimal PaymentIntent view. Its client secret is never included in log output. */
public record StripePaymentSnapshot(
        String id,
        Long amount,
        Long amountReceived,
        String currency,
        String status,
        boolean livemode,
        String orderReference,
        String clientSecret
) {
    @Override
    public String toString() {
        return "StripePaymentSnapshot["
                + "id=" + id
                + ", amount=" + amount
                + ", amountReceived=" + amountReceived
                + ", currency=" + currency
                + ", status=" + status
                + ", livemode=" + livemode
                + ", orderReference=" + orderReference
                + ", clientSecret=[REDACTED]]";
    }
}
