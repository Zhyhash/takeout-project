package org.example.takeout.Payment.Gateway;

/**
 * 一条已验签的 Stripe Webhook 事件快照。
 * <p>按事件类型二选一填充：支付事件带 {@code payment}，退款事件带 {@code refund}，
 * 其他类型两者均为 {@code null}。回执去重使用 {@code id}。</p>
 */
public record StripeWebhookEvent(
        String id,
        String type,
        boolean livemode,
        StripePaymentSnapshot payment,
        StripeRefundSnapshot refund
) {
}
