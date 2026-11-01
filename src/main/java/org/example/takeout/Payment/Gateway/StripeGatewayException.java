package org.example.takeout.Payment.Gateway;

import lombok.Getter;

/** Safe gateway error that never carries a Stripe response, request, or secret value. */
@Getter
public final class StripeGatewayException extends RuntimeException {
    public enum Kind {
        CONFIGURATION,
        REQUEST_FAILED,
        INVALID_WEBHOOK
    }

    private final Kind kind;

    public StripeGatewayException(Kind kind) {
        super(switch (kind) {
            case CONFIGURATION -> "Stripe test payment configuration is invalid";
            case REQUEST_FAILED -> "Stripe payment request failed";
            case INVALID_WEBHOOK -> "Stripe webhook signature is invalid";
        });
        this.kind = kind;
    }

}
