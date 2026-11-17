package org.example.takeout.Payment.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Payment.Entity.OrderStripeRefund;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderStripeRefundMapper extends BaseMapper<OrderStripeRefund> {
    @Select("""
    SELECT * FROM order_stripe_refunds WHERE order_id = #{orderId} FOR UPDATE
    """)
    OrderStripeRefund selectByOrderIdForUpdate(@Param("orderId") Long orderId);

    /**
     * 写入退款单号与最新远端状态。结束对账时同时清理下次重试时间。
     * <p>{@code WHERE} 条件限定"仍未取得终态"：调用方虽然已经用 {@code FOR UPDATE} 持有行锁，
     * 但数据库层再拦一次，可以保证已落库的终态不会被任何路径覆盖回 pending。</p>
     */
    @Update("""
    UPDATE order_stripe_refunds
    SET refund_id = #{refundId},
        stripe_status = #{newStatus},
        failure_reason = #{failureReason},
        reconciliation_state = #{reconciliationState},
        next_retry_at = CASE WHEN #{reconciliationState} = 'PENDING' THEN next_retry_at ELSE NULL END
    WHERE order_id = #{orderId}
      AND (stripe_status IS NULL OR stripe_status = 'pending')
    """)
    int updateStatus(@Param("orderId") Long orderId,
                     @Param("refundId") String refundId,
                     @Param("newStatus") String newStatus,
                     @Param("reconciliationState") String reconciliationState,
                     @Param("failureReason") String failureReason);

    @Update("""
    UPDATE order_stripe_refunds
    SET stripe_status = 'failed',
        failure_reason = #{failureReason},
        reconciliation_state = 'REVIEW',
        next_retry_at = NULL,
        update_time = NOW()
    WHERE order_id = #{orderId}
      AND refund_id = #{refundId}
      AND stripe_status = 'succeeded'
      AND reconciliation_state = 'RECONCILED'
    """)
    int updateStatusToFailed(
            @Param("orderId") Long orderId,
            @Param("refundId") String refundId,
            @Param("failureReason") String failureReason
    );
    /** 结束冲突回查并清除下次重试时间。 */
    @Update("""
    UPDATE order_stripe_refunds
    SET reconciliation_state = #{reconciliationState},
        next_retry_at = NULL
    WHERE order_id = #{orderId}
    """)
    int updateReconciliationState(@Param("orderId") Long orderId,
                                  @Param("reconciliationState") String reconciliationState);

    @Update("""
    UPDATE order_stripe_refunds
    SET reconciliation_state = #{reconciliationState},
        retry_count = #{retryCount},
        next_retry_at = #{nextRetryAt,jdbcType=TIMESTAMP}
    WHERE order_id = #{orderId}
    """)
    int updateRetrySchedule(@Param("orderId") Long orderId,
                            @Param("reconciliationState") String reconciliationState,
                            @Param("retryCount") int retryCount,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Select("""
    SELECT * FROM order_stripe_refunds
    WHERE order_id=#{orderId}
""")
    OrderStripeRefund selectRefundmentByOrderId(@Param("orderId") Long orderId);

    /** 退款 Webhook 只认退款单号：本地已绑定的退款记录靠它反查订单。 */
    @Select("""
    SELECT * FROM order_stripe_refunds
    WHERE refund_id = #{refundId}
""")
    OrderStripeRefund selectByRefundId(@Param("refundId") String refundId);

    /**
     * 退款单号还没落库时（远端创建结果未知），用 PaymentIntent 反查本地退款记录。
     * <p>每张支付单当前只对应一条退款记录，因此命中即认为该退款属于本订单。</p>
     */
    @Select("""
    SELECT r.* FROM order_stripe_refunds r
    JOIN order_stripe_payments p ON p.order_id = r.order_id
    WHERE p.payment_intent_id = #{paymentIntentId}
""")
    OrderStripeRefund selectByPaymentIntentId(@Param("paymentIntentId") String paymentIntentId);

    @Select("""
    SELECT *
    FROM order_stripe_refunds
    WHERE reconciliation_state = #{reconciliationState}
      AND (next_retry_at IS NULL OR next_retry_at <= NOW())
    ORDER BY next_retry_at, create_time
    LIMIT 1000;
    """)
    List<OrderStripeRefund> selectPendingRetryList(@Param("reconciliationState") String reconciliationState);

    @Update("""
    UPDATE order_stripe_refunds
    SET reconciliation_state = 'REVIEW',
        update_time = NOW()
    WHERE order_id = #{orderId}
      AND reconciliation_state = 'PENDING'
      AND refund_id IS NULL
    """)
    int markReconciliationReview(Long orderId);

    /**
     * 巡检"钱没退成"的退款记录。
     * <p>只读查询，供定时任务汇总告警；是否自动重试由业务按 {@code failure_reason} 判断，
     * 这里不做筛选也不写入，避免巡检本身改变状态机。</p>
     */
    @Select("""
    SELECT * FROM order_stripe_refunds
    WHERE stripe_status = 'failed'
    ORDER BY update_time, order_id
    LIMIT #{limit}
    """)
    List<OrderStripeRefund> selectFailedList(@Param("limit") int limit);
}
