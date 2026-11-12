package org.example.takeout.Order.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderCommandService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商家超时未接单自动取消。
 *
 * <p>扫描只负责发现「已支付满 10 分钟仍是 PAID」的候选订单；能否真正取消由
 * {@code OrderCommandService#cancelOrderByMerchantTimeout} 里的条件更新决定，
 * 因此在扫描与执行之间商家接了单，这次取消会自然落空，不会误退。</p>
 *
 * <p>只有启用 Stripe 退款时才需要这个任务：没有退款能力就不该自动取消已付款订单。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class OrderAcceptTimeoutTask {
    private static final int BATCH_SIZE = 100;

    private final OrderMapper orderMapper;
    private final OrderCommandService orderCommandService;

    @Scheduled(
            fixedDelayString = "${order.accept-timeout.scan-interval-ms:60000}",
            initialDelayString = "${order.accept-timeout.initial-delay-ms:60000}"
    )
    public void cancelUnacceptedOrders() {
        List<Long> orderIds = orderMapper.selectUnacceptedPaidOrderIds(
                OrderStatusEnum.PAID.getCode(),
                BATCH_SIZE
        );

        for (Long orderId : orderIds) {
            try {
                if (orderCommandService.cancelOrderByMerchantTimeout(orderId)) {
                    log.info("商家超时未接单，订单已转入退款，orderId={}", orderId);
                }
            } catch (BusinessException exception) {
                // 扫描到执行之间商家已接单、或状态已被别的入口改走，属于正常竞争，不算故障
                log.warn("订单已不满足超时取消条件，跳过，orderId={}，原因：{}",
                        orderId, exception.getMessage());
            } catch (Exception exception) {
                // 单个订单失败不影响同批其它订单
                log.error("超时取消订单失败，orderId={}", orderId, exception);
            }
        }
    }
}
