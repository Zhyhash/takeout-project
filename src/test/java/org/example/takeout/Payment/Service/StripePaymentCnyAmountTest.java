package org.example.takeout.Payment.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class StripePaymentCnyAmountTest {
    @Test
    void convertsRmbYuanToFenWithoutRoundingOrFx() {
        assertEquals(12_345L, StripePaymentService.toCnyMinor(new BigDecimal("123.45")));
        assertEquals(1L, StripePaymentService.toCnyMinor(new BigDecimal("0.01")));
        assertEquals(12_300L, StripePaymentService.toCnyMinor(new BigDecimal("123.000")));
        assertEquals(99_999_999L, StripePaymentService.toCnyMinor(new BigDecimal("999999.99")));
    }

    @Test
    void rejectsAmountsThatCannotBeExpressedAsValidStripeFen() {
        assertThrows(BusinessException.class, () -> StripePaymentService.toCnyMinor(new BigDecimal("0")));
        assertThrows(BusinessException.class, () -> StripePaymentService.toCnyMinor(new BigDecimal("-0.01")));
        assertThrows(BusinessException.class, () -> StripePaymentService.toCnyMinor(new BigDecimal("0.001")));
        assertThrows(BusinessException.class, () -> StripePaymentService.toCnyMinor(new BigDecimal("1000000.00")));
        assertThrows(BusinessException.class, () -> StripePaymentService.toCnyMinor(null));
    }
}
