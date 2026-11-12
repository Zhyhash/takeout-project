package org.example.takeout.Payment.Gateway;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;

public final class StripeRefundGatewayImpl implements  StripeRefundGateway {
    private final StripeClient stripeClient;

    public StripeRefundGatewayImpl(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    @Override
    public StripeRefundSnapshot createRefund(String paymentIntentId, long amountMinor, String idempotencyKey) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("paymentIntentId cannot be blank");
        }
        if (amountMinor <=0)
            throw new IllegalArgumentException("amountMinor cannot be negative");
        if (idempotencyKey == null)
            throw new IllegalArgumentException("idempotencyKey cannot be null");
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setAmount(amountMinor)
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();

        try {
            Refund refund = stripeClient.v1()
                    .refunds()
                    .create(params, options);
            return buildRefundSnapshot(refund);
        } catch (StripeException e) {
            throw StripeGatewayException.fromStripeFailure(e);
        }
    }

    @Override
    public StripeRefundSnapshot retrieveRefund(String refundId) {
        if (refundId == null || refundId.isBlank()) {
            throw new IllegalArgumentException("refundId cannot be blank");
        }
        try {
            Refund refund = stripeClient.v1()
                    .refunds()
                    .retrieve(refundId);
            return buildRefundSnapshot(refund);
        } catch (StripeException e) {
            throw StripeGatewayException.fromStripeFailure(e);
        }
    }

    private StripeRefundSnapshot buildRefundSnapshot(Refund refund) {
        if (refund == null
                || refund.getId() == null
                || refund.getId().isBlank()
                || refund.getAmount() == null
                || refund.getCurrency() == null
                || refund.getStatus() == null) {
            throw new StripeGatewayException(
                    StripeGatewayException.Kind.OUTCOME_UNKNOWN
            );
        }

        return new StripeRefundSnapshot(
                refund.getId(),
                refund.getPaymentIntent(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus()
        );
    }
}
