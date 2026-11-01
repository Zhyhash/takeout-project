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
        first.setLongitude(new java.math.BigDecimal("116.397"));
        first.setLatitude(new java.math.BigDecimal("39.908"));
        CreateOrderDTO second = TestDataFactory.createOrderDTO();
        second.setLongitude(new java.math.BigDecimal("116.397"));
        second.setLatitude(new java.math.BigDecimal("39.908"));
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
        first.setLongitude(new java.math.BigDecimal("116.397"));
        first.setLatitude(new java.math.BigDecimal("39.908"));
        CreateOrderDTO second = new CreateOrderDTO();
        second.setReceiverName("a");
        second.setReceiverPhone(":bc");
        second.setLongitude(new java.math.BigDecimal("116.397"));
        second.setLatitude(new java.math.BigDecimal("39.908"));
        assertNotEquals(fingerprint.calculate(first), fingerprint.calculate(second));
    }

    @Test
    void nullAndEmptyOptionalRemarkHaveDifferentFingerprints() {
        CreateOrderDTO first = TestDataFactory.createOrderDTO();
        first.setRemark(null);
        first.setLongitude(new java.math.BigDecimal("116.397"));
        first.setLatitude(new java.math.BigDecimal("39.908"));
        CreateOrderDTO second = TestDataFactory.createOrderDTO();
        second.setRemark("");
        second.setLongitude(new java.math.BigDecimal("116.397"));
        second.setLatitude(new java.math.BigDecimal("39.908"));
        assertNotEquals(fingerprint.calculate(first), fingerprint.calculate(second));
    }

    @Test
    void numericallyEquivalentCoordinateFormatsHaveTheSameFingerprint() {
        CreateOrderDTO first = TestDataFactory.createOrderDTO();
        first.setLongitude(new java.math.BigDecimal("116.397"));
        first.setLatitude(new java.math.BigDecimal("39.908"));
        CreateOrderDTO retry = TestDataFactory.createOrderDTO();
        retry.setLongitude(new java.math.BigDecimal("116.3970000"));
        retry.setLatitude(new java.math.BigDecimal("39.908000"));

        assertEquals(fingerprint.calculate(first), fingerprint.calculate(retry));
    }

    @Test
    void differentReceiverCoordinatesHaveDifferentFingerprints() {
        CreateOrderDTO first = TestDataFactory.createOrderDTO();
        first.setLongitude(new java.math.BigDecimal("116.397"));
        first.setLatitude(new java.math.BigDecimal("39.908"));
        CreateOrderDTO changed = TestDataFactory.createOrderDTO();
        changed.setLongitude(new java.math.BigDecimal("116.398"));
        changed.setLatitude(new java.math.BigDecimal("39.908"));

        assertNotEquals(fingerprint.calculate(first), fingerprint.calculate(changed));
    }
}
