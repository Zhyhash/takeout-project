package org.example.takeout.Payment.Config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.Service.StripeRefundService;
import org.example.takeout.Payment.Service.StripeRefundTransactionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeRefundRecoveryTask {
    private final OrderStripeRefundMapper orderStripeRefundMapper;
    private final StripeRefundService stripeRefundService;
    private final StripeRefundTransactionService stripeRefundTransactionService;

    public StripeRefundRecoveryTask(OrderStripeRefundMapper orderStripeRefundMapper, StripeRefundService stripeRefundService, StripeRefundTransactionService stripeRefundTransactionService) {
        this.orderStripeRefundMapper = orderStripeRefundMapper;
        this.stripeRefundService = stripeRefundService;
        this.stripeRefundTransactionService = stripeRefundTransactionService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverPendingRefunds() {
        // 1. 查询 reconciliation_state = PENDING 的退款记录
        List<OrderStripeRefund> orderStripeRefunds = orderStripeRefundMapper.selectPendingRetryList(ReconciliationState.PENDING.code());
        if (orderStripeRefunds.isEmpty()) {
            return;
        }
        // 2. 对每条记录执行恢复
        for (OrderStripeRefund orderStripeRefund : orderStripeRefunds) {
            try {
                stripeRefundService.recoverRefund(orderStripeRefund);
            } catch (Exception e) {
                log.error("stripe自动恢复失败，orderID={}",orderStripeRefund.getOrderId(),e);
                stripeRefundTransactionService.scheduleConflictRetry(orderStripeRefund.getOrderId());

            }
        }

    }
}
