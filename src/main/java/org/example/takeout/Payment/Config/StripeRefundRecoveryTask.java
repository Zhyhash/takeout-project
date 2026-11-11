package org.example.takeout.Payment.Config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.Service.StripeRefundService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class StripeRefundRecoveryTask {
    private final OrderStripeRefundMapper orderStripeRefundMapper;
    private final StripeRefundService stripeRefundService;

    public StripeRefundRecoveryTask(OrderStripeRefundMapper orderStripeRefundMapper, StripeRefundService stripeRefundService) {
        this.orderStripeRefundMapper = orderStripeRefundMapper;
        this.stripeRefundService = stripeRefundService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverPendingRefunds() {
        // 1. 查询 reconciliation_state = PENDING 的退款记录
        List<OrderStripeRefund> orderStripeRefunds = orderStripeRefundMapper.selectList(Wrappers.<OrderStripeRefund>lambdaQuery().
                eq(OrderStripeRefund::getReconciliationState, ReconciliationState.PENDING.code()).
                orderByAsc(OrderStripeRefund::getCreateTime).
                last("LIMIT 1000"));
        if (orderStripeRefunds.isEmpty()) {
            return;
        }
        // 2. 对每条记录执行恢复
        for (OrderStripeRefund orderStripeRefund : orderStripeRefunds) {
            try {
                stripeRefundService.recoverRefund(orderStripeRefund);
            } catch (Exception e) {
                int affected = orderStripeRefundMapper.update(
                        null,
                        Wrappers.<OrderStripeRefund>lambdaUpdate()
                                .eq(OrderStripeRefund::getOrderId,
                                        orderStripeRefund.getOrderId())
                                .eq(OrderStripeRefund::getReconciliationState,
                                        ReconciliationState.PENDING.code())
                                .set(OrderStripeRefund::getReconciliationState,
                                        ReconciliationState.REVIEW.code())
                );
                if (affected == 0) {
                    log.warn("退款状态已变化，跳过标记 REVIEW，orderId={}",
                            orderStripeRefund.getOrderId());
                }
                // 3. 单条失败记录日志，不影响其他记录
                log.error("stripe自动恢复失败，orderID={}",orderStripeRefund.getOrderId(),e);
            }
        }

    }
}
