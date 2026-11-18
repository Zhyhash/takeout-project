package org.example.takeout.Payment.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;
import org.example.takeout.Payment.Mapper.OrderStripeRefundAttemptMapper;
import org.example.takeout.Payment.Service.StripeRefundService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeRefundRecoveryTask {
    private final OrderStripeRefundAttemptMapper attemptMapper;
    private final StripeRefundService stripeRefundService;

    @Scheduled(fixedDelay = 60_000)
    public void recoverPendingRefunds() {
        for (OrderStripeRefundAttempt attempt : attemptMapper.selectPendingRetryList(1000)) {
            try {
                stripeRefundService.recoverRefund(attempt);
            } catch (RuntimeException failure) {
                log.error("Stripe 退款恢复失败，orderId={}, scannedAttemptId={}",
                        attempt.getOrderId(), attempt.getId(), failure);
            }
        }
    }
}
