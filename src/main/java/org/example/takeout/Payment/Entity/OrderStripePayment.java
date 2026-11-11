package org.example.takeout.Payment.Entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 订单的 Stripe 支付关联记录，对应 order_stripe_payments 表；订单业务状态仍由订单表维护。 */
@Data
public class OrderStripePayment {
    /** 关联的订单 ID，同时作为本表主键，保证每笔订单只有一条支付记录。 */
    private Long orderId;

    /** Stripe 支付意图 ID，用于查询支付结果和匹配回调；尚未完成本地关联时为空。 */
    private String paymentIntentId;

    /** 以人民币分为单位的支付金额，由服务端订单总额精确换算得到。 */
    private Long amountMinor;

    /** Stripe 币种代码，当前固定为 cny，表示人民币。 */
    private String currency;

    /** 本地保存的 Stripe 原生支付状态，用于记录远端支付进度，不替代订单业务状态。 */
    private String stripeStatus;

    /** 创建支付意图时使用的稳定幂等键，重试时复用，防止重复创建远端支付意图。 */
    private String idempotencyKey;

    /** 本地支付记录的创建时间，用于追踪支付准备时间。 */
    private LocalDateTime createTime;

    /** 本地支付关联或状态的最近更新时间，由写入支付信息的 SQL 维护。 */
    private LocalDateTime updateTime;

    /** Local coordination state, independent of the Stripe status: PENDING, COMPLETE, REVIEW. */
    private String reconciliationState;

    /** 已进行的恢复尝试次数，用于计算退避间隔并设置有上限，避免无限重试。 */
    private Integer retryCount;

    /** 下一次允许恢复扫描到这笔记录的时间；同时也充当"本次尝试已占位"的标记。 */
    private LocalDateTime nextRetryAt;
}
