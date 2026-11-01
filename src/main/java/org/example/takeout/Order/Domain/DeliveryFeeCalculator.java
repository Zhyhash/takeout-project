package org.example.takeout.Order.Domain;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 配送报酬计算服务。
 * 负责提供配送任务的骑手报酬金额：3 公里以内固定 5 元，
 * 超出部分每公里（向上取整）加收 1.5 元。
 */
@Service
public class DeliveryFeeCalculator {

    /**
     * 最大配送距离（千米）：平台只服务同城短距离订单，
     * 超过该距离视为跨城/超区订单，拒绝创建。
     */
    public static final double MAX_DELIVERY_DISTANCE_KM = 30.0;

    public BigDecimal calculateDeliveryReward(double distanceKm) {

        if (!Double.isFinite(distanceKm) || distanceKm < 0) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "配送距离不合法");
        }

        if (distanceKm > MAX_DELIVERY_DISTANCE_KM) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "配送距离超出服务范围（最长" + (long) MAX_DELIVERY_DISTANCE_KM + "公里），暂时无法下单");
        }

        if (distanceKm <= 3.0) {
            return new BigDecimal("5.00");
        }

        long extraKm = (long) Math.ceil(distanceKm - 3.0);

        return new BigDecimal("5.00")
                .add(new BigDecimal("1.50")
                        .multiply(BigDecimal.valueOf(extraKm)));
    }

}
