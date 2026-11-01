package org.example.takeout.Order.Domain;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryFeeCalculatorTest {

    private final DeliveryFeeCalculator calculator = new DeliveryFeeCalculator();

    @ParameterizedTest
    @CsvSource({
            "0.0, 5.00",
            "3.0, 5.00",
            "3.000001, 6.50",
            "4.0, 6.50",
            "4.000001, 8.00",
            "30.0, 45.50"
    })
    void calculatesBaseAndPerKilometerFeeAtBoundaries(double distanceKm, String expectedFee) {
        assertEquals(new BigDecimal(expectedFee), calculator.calculateDeliveryReward(distanceKm));
    }

    @Test
    void rejectsDistanceBeyondThirtyKilometers() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> calculator.calculateDeliveryReward(30.000001));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("配送距离超出服务范围（最长30公里），暂时无法下单", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"NaN", "-0.001"})
    void rejectsNanAndNegativeDistance(double distanceKm) {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> calculator.calculateDeliveryReward(distanceKm));

        assertEquals(ResultCodeEnum.PARAM_ERROR, failure.getCodeEnum());
        assertEquals("配送距离不合法", failure.getMessage());
    }
}
