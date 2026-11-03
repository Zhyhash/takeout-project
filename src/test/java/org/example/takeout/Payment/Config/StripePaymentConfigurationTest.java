package org.example.takeout.Payment.Config;

import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class StripePaymentConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StripePaymentConfiguration.class);

    @Test
    void gatewayIsAbsentWhenStripePaymentsAreDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(StripePaymentGateway.class);
        });
    }

    @Test
    void enabledConfigurationAcceptsOnlyRestrictedTestKeys() {
        StripePaymentProperties properties = validProperties();
        StripePaymentConfiguration.validateStripeTestCredentials(properties);

        properties.setSecretKey("sk_test_not_restricted");
        StripePaymentProperties unrestrictedProperties = properties;
        assertThatIllegalStateException()
                .isThrownBy(() -> StripePaymentConfiguration.validateStripeTestCredentials(unrestrictedProperties))
                .withMessage("payment.stripe.secret-key must be a restricted Stripe test key (rk_test_)");

        properties = validProperties();
        properties.setSecretKey("rk_live_rejected");
        StripePaymentProperties liveKeyProperties = properties;
        assertThatIllegalStateException()
                .isThrownBy(() -> StripePaymentConfiguration.validateStripeTestCredentials(liveKeyProperties));
    }

    @Test
    void enabledConfigurationRequiresTestPublishableAndWebhookKeys() {
        StripePaymentProperties properties = validProperties();
        properties.setPublishableKey("pk_live_rejected");
        StripePaymentProperties livePublishableProperties = properties;
        assertThatIllegalStateException()
                .isThrownBy(() -> StripePaymentConfiguration.validateStripeTestCredentials(livePublishableProperties));

        properties = validProperties();
        properties.setWebhookSecret("not-a-signing-secret");
        StripePaymentProperties invalidWebhookKeyProperties = properties;
        assertThatIllegalStateException()
                .isThrownBy(() -> StripePaymentConfiguration.validateStripeTestCredentials(invalidWebhookKeyProperties));
    }

    @Test
    void enabledConfigurationStartupFailureDoesNotExposeConfiguredKeyValues() {
        String testSecretPlaceholder = "rk_test_placeholder_secret_value";
        String livePublishablePlaceholder = "pk_live_placeholder_publishable_value";
        String webhookPlaceholder = "whsec_placeholder_webhook_value";

        contextRunner.withPropertyValues(
                "payment.stripe.enabled=true",
                "payment.stripe.secret-key=" + testSecretPlaceholder,
                "payment.stripe.publishable-key=" + livePublishablePlaceholder,
                "payment.stripe.webhook-secret=" + webhookPlaceholder)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    StringBuilder failureChain = new StringBuilder();
                    while (failure != null) {
                        failureChain.append(failure).append('\n');
                        failure = failure.getCause();
                    }

                    assertThat(failureChain.toString())
                            .contains("payment.stripe.publishable-key must be a Stripe test key")
                            .doesNotContain(testSecretPlaceholder, livePublishablePlaceholder, webhookPlaceholder);
                });
    }

    private static StripePaymentProperties validProperties() {
        StripePaymentProperties properties = new StripePaymentProperties();
        properties.setEnabled(true);
        properties.setSecretKey("rk_test_placeholder");
        properties.setPublishableKey("pk_test_placeholder");
        properties.setWebhookSecret("whsec_placeholder");
        return properties;
    }
}
