package org.example.takeout.Payment.Domain;

/**
 * 本地对账协调状态，与 Stripe 的支付状态相互独立。
 * <p>原本以字符串形式出现在 8 处赋值与比较里（PENDING / COMPLETE / REVIEW），
 * 且数据库有 CHECK 约束，改成枚举后拼写错误会在编译期暴露。</p>
 * <p>数据库列 {@code reconciliation_state} 仍存枚举名本身，因此 {@link #name()} 即落库值。</p>
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
