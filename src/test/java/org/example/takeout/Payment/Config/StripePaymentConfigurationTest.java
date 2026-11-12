package org.example.takeout.Payment.Config;

import com.stripe.StripeClient;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.Service.StripeRefundService;
import org.example.takeout.Payment.Service.StripeRefundTransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

class StripePaymentConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StripePaymentConfiguration.class, RefundTestConfiguration.class);

    @Test
    void stripeBeansAreAbsentWhenStripePaymentsAreDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(StripePaymentGateway.class);
            assertThat(context).doesNotHaveBean(StripeRefundGateway.class);
            assertThat(context).doesNotHaveBean(StripeClient.class);
            assertThat(context).doesNotHaveBean(StripeRefundService.class);
            assertThat(context).doesNotHaveBean(StripeRefundTransactionService.class);
            assertThat(context).doesNotHaveBean(StripeRefundRecoveryTask.class);
        });
    }

    @Test
    void paymentAndRefundGatewaysShareTheConfiguredStripeClient() {
        contextRunner.withPropertyValues("payment.stripe.enabled=true", "payment.stripe.secret-key=rk_test_placeholder",
                        "payment.stripe.publishable-key=pk_test_placeholder", "payment.stripe.webhook-secret=whsec_placeholder")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    StripeClient stripeClient = context.getBean(StripeClient.class);
                    StripePaymentGateway paymentGateway = context.getBean(StripePaymentGateway.class);
                    StripeRefundGateway refundGateway = context.getBean(StripeRefundGateway.class);

                    assertThat(context).hasSingleBean(StripeRefundService.class);
                    assertThat(context).hasSingleBean(StripeRefundTransactionService.class);
                    assertThat(context).hasSingleBean(StripeRefundRecoveryTask.class);
                    assertThat(ReflectionTestUtils.getField(paymentGateway, "stripeClient"))
                            .isSameAs(stripeClient);
                    assertThat(ReflectionTestUtils.getField(refundGateway, "stripeClient"))
                            .isSameAs(stripeClient);
                });
    }

    @Test
    void recoverySchedulerIsSeparateFromTheDefaultOrderTimeoutScheduler() {
        contextRunner.withPropertyValues("payment.stripe.enabled=true", "payment.stripe.secret-key=rk_test_placeholder",
                "payment.stripe.publishable-key=pk_test_placeholder", "payment.stripe.webhook-secret=whsec_placeholder")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("stripePaymentRecoveryScheduler"))
                            .isNotSameAs(context.getBean("taskScheduler"));
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

    @TestConfiguration(proxyBeanMethods = false)
    @Import({StripeRefundService.class, StripeRefundTransactionService.class, StripeRefundRecoveryTask.class})
    static class RefundTestConfiguration {
        @Bean
        OrderMapper orderMapper() {
            return mock(OrderMapper.class);
        }

        @Bean
        OrderStripePaymentMapper orderStripePaymentMapper() {
            return mock(OrderStripePaymentMapper.class);
        }

        @Bean
        OrderStripeRefundMapper orderStripeRefundMapper() {
            return mock(OrderStripeRefundMapper.class);
        }

        @Bean
        OrderItemService orderItemService() {
            return mock(OrderItemService.class);
        }
    }
}
