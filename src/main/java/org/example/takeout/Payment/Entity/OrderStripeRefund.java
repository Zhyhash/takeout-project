package org.example.takeout.Payment.Entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 订单的 Stripe 退款记录，对应 order_stripe_refunds 表。 */
@Data
@TableName("order_stripe_refunds")
public class OrderStripeRefund {
    /** 关联订单 ID，同时作为主键，每笔订单最多保存一条退款记录。 */
    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;

    /** Stripe 退款对象 ID；远端创建结果尚未关联时为空。 */
    private String refundId;

    /** 退款金额，单位为对应币种的最小货币单位。 */
    private Long amountMinor;

    /** Stripe 币种代码。 */
    private String currency;

    /** 创建退款时使用的稳定幂等键，同一退款操作重试时复用。 */
    private String idempotencyKey;

    /** 本地保存的 Stripe 原生退款状态。 */
    private String stripeStatus;

    /** 本地退款对账协调状态，与 Stripe 退款状态独立。 */
    private String reconciliationState;

    /** 已记录的退款状态冲突次数。 */
    private Integer retryCount;

    /** 下次允许恢复的数据库时间，空值表示无需等待。 */
    private LocalDateTime nextRetryAt;

    /** 退款记录创建时间。 */
    private LocalDateTime createTime;

    /** 退款记录最近更新时间，由数据库在记录变更时自动维护。 */
    private LocalDateTime updateTime;
}
