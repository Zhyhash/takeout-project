package org.example.takeout.Payment.Domain;

/** {@code order_stripe_refunds.status} 存储的退款业务状态。 */
public enum RefundStatus {
    /** 退款业务尚未完成，仍需处理。 */
    PENDING,
    /** 退款业务需要人工介入。 */
    REVIEW,
    /** 整笔退款业务已完成。 */
    SUCCEEDED;

    /** 返回与数据库约束一致的状态值。 */
    public String code() {
        return name();
    }

    /** 精确比较落库值，空值或不匹配时返回 false。 */
    public boolean matches(String raw) {
        return code().equals(raw);
    }
}
