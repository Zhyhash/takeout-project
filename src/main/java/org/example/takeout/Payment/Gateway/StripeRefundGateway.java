package org.example.takeout.Payment.Gateway;

public interface StripeRefundGateway {
    StripeRefundSnapshot createRefund(
            String paymentIntentId,
            long amountMinor,
            String idempotencyKey
    );

    StripeRefundSnapshot retrieveRefund(String refundId);
}
