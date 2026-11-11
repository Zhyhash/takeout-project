package org.example.takeout.Payment.Gateway;

public record StripeRefundSnapshot(
        String refundId,
        String paymentIntentId,
        Long amountMinor,
        String currency,
        String status
) {
}
