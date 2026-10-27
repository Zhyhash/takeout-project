package org.example.takeout.DeliveryTask.Domain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 配送报酬计算服务。
 * 负责提供配送任务的骑手报酬金额，当前采用固定金额规则。
 */
@Service
public class DeliveryFeeCalculator {
    public BigDecimal calculateDeliveryReward() {
        return BigDecimal.valueOf(5);
    }
}
