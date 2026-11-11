package org.example.takeout.Payment.VO;

import org.example.takeout.Payment.Entity.OrderStripeRefund;

public record RefundPreparation(
        OrderStripeRefund refund,
        boolean newlyCreated
) {}
