package org.example.takeout.Payment.Entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 订单的 Stripe 退款业务记录。 */
@Data
@TableName("order_stripe_refunds")
public class OrderStripeRefund {
    /** 关联订单 ID，同时作为主键，每笔订单最多保存一条退款记录。 */
    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;

    /** 退款金额，单位为对应币种的最小货币单位。 */
    private Long amountMinor;

    /** Stripe 币种代码。 */
    private String currency;

    /** 退款业务状态：PENDING 待处理，SUCCEEDED 已完成，REVIEW 待人工介入。 */
    private String status;

    /** 退款记录创建时间。 */
    private LocalDateTime createTime;

    /** 退款记录最近更新时间，由数据库在记录变更时自动维护。 */
    private LocalDateTime updateTime;
}
