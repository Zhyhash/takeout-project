package org.example.takeout.DeliveryTask.Domain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class DeliveryFeeCalculator {
    public BigDecimal calculateDeliveryReward() {
        return BigDecimal.valueOf(5);
    }
}
