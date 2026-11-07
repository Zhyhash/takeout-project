package org.example.takeout.Payment.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Gateway.StripeGatewayException;
import org.example.takeout.Payment.Service.StripePaymentService;
import org.example.takeout.Payment.Service.StripePaymentTransactionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Uses database leases/backoff; no database transaction spans an individual recovery HTTP request. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripePaymentRecoveryTask {
    private final StripePaymentTransactionService transactions;
    private final StripePaymentService payments;
    @Value("${payment.stripe.recovery-batch-size:50}")
    private int batchSize = 50;

    @Scheduled(scheduler = "stripePaymentRecoveryScheduler",
            initialDelayString = "${payment.stripe.recovery-initial-delay-ms:15000}",
            fixedDelayString = "${payment.stripe.recovery-delay-ms:15000}")
    public void recoverPendingPayments() {
        for (Long orderId : transactions.findRecoveryOrderIds(
                Math.max(1, Math.min(PaymentPolicy.MAX_RECOVERY_BATCH, batchSize)))) {
            try {
                payments.recoverOrderPayment(orderId);
            } catch (RuntimeException failure) {
                // 只记录异常分类与 Stripe 错误码，不输出响应体、异常 message 或客户端密钥。
                log.warn("Stripe payment recovery deferred: orderId={}, failure={}, kind={}, stripeCode={}",
                        orderId, failure.getClass().getSimpleName(), kindOf(failure), codeOf(failure));
            }
        }
    }

    private static String kindOf(RuntimeException failure) {
        return failure instanceof StripeGatewayException gatewayFailure ? gatewayFailure.getKind().name() : "-";
    }

    private static String codeOf(RuntimeException failure) {
        return failure instanceof StripeGatewayException gatewayFailure
                ? String.valueOf(gatewayFailure.getStripeErrorCode()) : "-";
    }
}
