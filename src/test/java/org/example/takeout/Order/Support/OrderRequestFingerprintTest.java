package org.example.takeout.Order.Support;

import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderRequestFingerprintTest {
    private final OrderRequestFingerprint fingerprint = new OrderRequestFingerprint();

    @Test
    void requestIdDoesNotChangeTheParameterFingerprint() {
        CreateOrderDTO first = TestDataFactory.createOrderDTO();
        CreateOrderDTO second = TestDataFactory.createOrderDTO();
        assertNotEquals(first.getRequestId(), second.getRequestId());
        String hash = fingerprint.calculate(first);
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, fingerprint.calculate(second));
    }

    @Test
    void fieldBoundariesRemainDistinctEvenWhenConcatenatedTextIsEqual() {
        CreateOrderDTO first = new CreateOrderDTO();
        first.setReceiverName("a:");
        first.setReceiverPhone("bc");
        CreateOrderDTO second = new CreateOrderDTO();
        second.setReceiverName("a");
        second.setReceiverPhone(":bc");
        assertNotEquals(fingerprint.calculate(first), fingerprint.calculate(second));
    }

    @Test
    void nullAndEmptyOptionalRemarkHaveDifferentFingerprints() {
        CreateOrderDTO first = TestDataFactory.createOrderDTO();
        first.setRemark(null);
        CreateOrderDTO second = TestDataFactory.createOrderDTO();
        second.setRemark("");
        assertNotEquals(fingerprint.calculate(first), fingerprint.calculate(second));
    }
}
