package org.example.takeout.Payment.Config;

import com.stripe.StripeClient;
import org.example.takeout.Payment.Gateway.StripePaymentGateway;
import org.example.takeout.Payment.Gateway.StripePaymentGatewayImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(StripePaymentProperties.class)
public class StripePaymentConfiguration {
    // Defining the dedicated scheduler makes Boot back off its default scheduler, so retain that default explicitly.
    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    @ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }

    /** Remote payment retries must not occupy the default scheduler used by order timeout cancellation. */
    @Bean
    @ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
    public ThreadPoolTaskScheduler stripePaymentRecoveryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stripe-recovery-");
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
    public StripePaymentGateway stripePaymentGateway(StripePaymentProperties properties) {
        validateStripeTestCredentials(properties);

        StripeClient client = StripeClient.builder()
                .setApiKey(properties.getSecretKey())
                .setConnectTimeout(5_000)
                .setReadTimeout(10_000)
                .setMaxNetworkRetries(1)
                .build();

        return new StripePaymentGatewayImpl(client, properties.getWebhookSecret());
    }

    static void validateStripeTestCredentials(StripePaymentProperties properties) {
        if (properties.getSecretKey() == null || !properties.getSecretKey().startsWith("rk_test_")) {
            throw new IllegalStateException("payment.stripe.secret-key must be a restricted Stripe test key (rk_test_)");
        }

        if (properties.getPublishableKey() == null || !properties.getPublishableKey().startsWith("pk_test_")) {
            throw new IllegalStateException("payment.stripe.publishable-key must be a Stripe test key (pk_test_)");
        }

        if (properties.getWebhookSecret() == null || !properties.getWebhookSecret().startsWith("whsec_")) {
            throw new IllegalStateException("payment.stripe.webhook-secret must be a Stripe webhook signing secret (whsec_)");
        }
    }
}
