package org.example.takeout.Order.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "order.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderTimeoutTask {
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    @Scheduled(
            fixedDelayString = "${order.timeout.scan-interval-ms:60000}",
            initialDelayString = "${order.timeout.initial-delay-ms:60000}"
    )
    public void cancelTimeoutOrders() {
        LocalDateTime scanNow = LocalDateTime.now();
        LocalDateTime expiredBefore = scanNow.minusMinutes(30);
        List<Long> orderIds = orderMapper.selectTimeoutOrderIds(
                OrderStatusEnum.WAIT_PAY.getCode(),
                scanNow,
                100
        );

        for (Long orderId : orderIds) {
            try {
                if (orderService.cancelTimeoutOrder(orderId, expiredBefore)) {
                    log.info("超时订单已自动取消，orderId={}", orderId);
                }
            } catch (Exception exception) {
                log.error("自动取消超时订单失败，orderId={}", orderId, exception);
                try {
                    orderMapper.delayTimeoutCancellation(
                            orderId,
                            OrderStatusEnum.WAIT_PAY.getCode()
                    );
                } catch (Exception delayException) {
                    log.error(
                            "记录超时订单退避失败，orderId={}",
                            orderId,
                            delayException
                    );
                }
            }
        }
    }
}
