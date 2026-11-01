package org.example.takeout.Payment.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the test-only Stripe payment gateway.
 * Secret values are deliberately not exposed through toString().
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "payment.stripe")
public class StripePaymentProperties {
    private boolean enabled;
    private String secretKey;
    private String publishableKey;
    private String webhookSecret;

}
