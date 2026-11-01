package org.example.takeout.Payment.Entity;

import lombok.Data;
import java.time.LocalDateTime;

/** Stripe reference and observations; the order remains the business state machine. */
@Data
public class OrderStripePayment {
    private Long orderId;
    private String paymentIntentId;
    private Long amountMinor;
    private String currency;
    private String stripeStatus;
    private String idempotencyKey;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
