package org.example.takeout.Payment.VO;

public record StripePaymentView(Long orderId, String paymentIntentId, String clientSecret,
                                String publishableKey, Long amountMinor, String currency,
                                String paymentStatus, Integer orderStatus) {
    @Override
    public String toString() {
        return "StripePaymentView[orderId=" + orderId + ", paymentStatus=" + paymentStatus
                + ", orderStatus=" + orderStatus + "]";
    }
}
