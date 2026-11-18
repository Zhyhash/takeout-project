package org.example.takeout.Payment.Entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次 Stripe 退款尝试及其对账信息。 */
@Data
@TableName("order_stripe_refund_attempts")
public class OrderStripeRefundAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联退款主记录的订单 ID。 */
    private Long orderId;

    /** Stripe 退款对象 ID，远端结果尚未关联时为空。 */
    private String refundId;

    /** 同一次退款尝试重复提交时复用的幂等键。 */
    private String idempotencyKey;

    /** Stripe 原生退款状态。 */
    private String stripeStatus;

    /** 当前尝试的本地对账状态。 */
    private String reconciliationState;

    /** Stripe 返回的退款失败原因。 */
    private String failureReason;

    /** 已记录的该次尝试的退款状态冲突次数。 */
    private Integer retryCount;

    /** 下次允许对账恢复的时间，空值表示无需等待。 */
    private LocalDateTime nextRetryAt;

    /** 退款尝试创建时间，用于计算该尝试的幂等安全窗口。 */
    private LocalDateTime createTime;

    /** 退款尝试最近更新时间，由数据库自动维护。 */
    private LocalDateTime updateTime;
}
