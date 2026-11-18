package org.example.takeout.Payment.VO;

import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;

/** A persisted refund attempt ready for a Stripe request or recovery lookup. */
public record RefundExecution(
        OrderStripeRefundAttempt attempt,
        Long amountMinor,
        String paymentIntentId
) {
}
