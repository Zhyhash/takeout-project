package org.example.takeout.Payment.Domain;

/**
 * 支付和退款共用的本地对账协调状态，与 Stripe 原生状态及订单业务状态相互独立。
 * <p>数据库列 {@code reconciliation_state} 存储枚举名。</p>
 */
public enum ReconciliationState {
    /** 仍需后台恢复任务继续推进，{@code next_retry_at} 决定何时到期。 */
    PENDING,
    /** 已取得一致，不需要再额外恢复，并不代表完成退款。 */
    RECONCILED,
    /** 需要人工核实，恢复任务不再自动处理。 */
    REVIEW;

    /** 落库值。使用枚举名而非另起字段，避免名称与库值出现两套真相。 */
    public String code() {
        return name();
    }

    public boolean isPending() {
        return this == PENDING;
    }

    /** 是否为"需要人工介入"的状态。 */
    public boolean isReview() {
        return this == REVIEW;
    }

    /** 读库字符串比较，null 安全，未知值返回 false。 */
    public boolean matches(String raw) {
        return name().equals(raw);
    }

    /** 是否为可继续自动恢复的状态；未知值与 REVIEW 一样不再自动推进。 */
    public static boolean isPending(String raw) {
        return PENDING.matches(raw);
    }
}
