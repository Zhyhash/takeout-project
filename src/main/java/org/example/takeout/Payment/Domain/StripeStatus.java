package org.example.takeout.Payment.Domain;

/**
 * Stripe PaymentIntent 的生命周期状态。
 * <p>这些字符串原本散落在支付服务与事务服务的多处 if 判断中，读者必须靠记忆维持一张
 * "哪些状态算结束"的隐形字典。集中到枚举后，所有判断都通过语义方法表达：
 * {@link #isTerminal()}、{@link #isSucceeded()}、{@link #isCanceled()}。</p>
 * <p>数据库与 Stripe 报文里仍然是大写以外的原始字符串，因此 {@link #code()} 才是落库/出参值。</p>
 */
public enum StripeStatus {
    REQUIRES_PAYMENT_METHOD("requires_payment_method"),
    REQUIRES_CONFIRMATION("requires_confirmation"),
    REQUIRES_ACTION("requires_action"),
    PROCESSING("processing"),
    REQUIRES_CAPTURE("requires_capture"),
    CANCELED("canceled"),
    SUCCEEDED("succeeded");

    private final String code;

    StripeStatus(String code) {
        this.code = code;
    }

    /** 落库与出参使用的原始状态字符串。 */
    public String code() {
        return code;
    }

    /** 终态：不会再变，也是唯一可以结束本地对账的两个状态。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELED;
    }

    public boolean isSucceeded() {
        return this == SUCCEEDED;
    }

    public boolean isCanceled() {
        return this == CANCELED;
    }

    /**
     * 判断原始字符串是否等于某个状态。null 安全，未知状态一律返回 false，
     * 与原本的 {@code "succeeded".equals(raw)} 行为完全一致。
     */
    public boolean matches(String raw) {
        return code.equals(raw);
    }

    /** 原始字符串是否属于 Stripe 已知状态，用于快照校验白名单。 */
    public static boolean isKnown(String raw) {
        return parseOrNull(raw) != null;
    }

    /** 解析原始字符串；未知或为空时返回 null，由调用方决定拒绝还是保守处理。 */
    public static StripeStatus parseOrNull(String raw) {
        for (StripeStatus status : values()) {
            if (status.code.equals(raw)) {
                return status;
            }
        }
        return null;
    }
}
