package org.example.takeout.Payment.Gateway;

/**
 * 一笔 Stripe 退款的最小视图。
 *
 * @param failureReason 退款失败原因，仅 {@code failed} 状态时可能有值，其余为 {@code null}
 */
public record StripeRefundSnapshot(
        String refundId,
        String paymentIntentId,
        Long amountMinor,
        String currency,
        String status,
        String failureReason,
        String attemptKey
) {
    static final String ATTEMPT_METADATA_KEY = "refund_attempt_key";

    public StripeRefundSnapshot(String refundId, String paymentIntentId, Long amountMinor, String currency,
                                String status, String failureReason) {
        this(refundId, paymentIntentId, amountMinor, currency, status, failureReason, null);
    }
}
