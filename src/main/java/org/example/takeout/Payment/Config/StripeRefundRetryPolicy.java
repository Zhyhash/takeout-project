package org.example.takeout.Payment.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/** 仅控制已确认失败的退款能否自动发起下一笔。 */
@ConfigurationProperties(prefix = "payment.stripe.refund")
public record StripeRefundRetryPolicy(Set<String> autoRetryFailureReasons) {
    public StripeRefundRetryPolicy {
        autoRetryFailureReasons = autoRetryFailureReasons == null ? Set.of() : Set.copyOf(autoRetryFailureReasons);
    }

    public boolean allowsNewRefund(String failureReason) {
        return failureReason != null && !failureReason.isBlank() && autoRetryFailureReasons.contains(failureReason);
    }
}
