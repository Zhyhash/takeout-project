package org.example.takeout.Payment.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderStripeRefundAttemptMapper extends BaseMapper<OrderStripeRefundAttempt> {
    @Select("SELECT * FROM order_stripe_refund_attempts WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1")
    OrderStripeRefundAttempt selectLatestAttemptByOrderId(@Param("orderId") Long orderId);

    /** 主退款行应先加锁，再调用此方法锁住当前最新尝试。 */
    @Select("""
            SELECT * FROM order_stripe_refund_attempts
            WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    OrderStripeRefundAttempt selectLatestAttemptByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Select("SELECT * FROM order_stripe_refund_attempts WHERE id = #{attemptId} FOR UPDATE")
    OrderStripeRefundAttempt selectAttemptByIdForUpdate(@Param("attemptId") Long attemptId);

    @Select("SELECT * FROM order_stripe_refund_attempts WHERE refund_id = #{refundId}")
    OrderStripeRefundAttempt selectAttemptByRefundId(@Param("refundId") String refundId);

    @Select("SELECT * FROM order_stripe_refund_attempts WHERE idempotency_key = #{idempotencyKey}")
    OrderStripeRefundAttempt selectAttemptByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 按锁定的 Attempt 和既有绑定写快照，避免将终态回退到 pending。 */
    @Update("""
    UPDATE order_stripe_refund_attempts
    SET refund_id = #{refundId},
        stripe_status = #{status},
        reconciliation_state = #{reconciliationState},
        failure_reason = #{failureReason},
        next_retry_at = CASE WHEN #{reconciliationState} = 'PENDING' THEN next_retry_at ELSE NULL END
    WHERE id = #{attemptId}
      AND ((#{expectedRefundId,jdbcType=VARCHAR} IS NULL AND refund_id IS NULL)
           OR refund_id = #{expectedRefundId})
      AND (stripe_status IS NULL OR stripe_status = 'pending' OR stripe_status = #{status}
           OR (stripe_status IN ('succeeded', 'failed', 'canceled')
               AND #{status} IN ('succeeded', 'failed', 'canceled')))
    """)
    int updateAttemptStatus(@Param("attemptId") Long attemptId,
                            @Param("expectedRefundId") String expectedRefundId,
                            @Param("refundId") String refundId,
                            @Param("status") String status,
                            @Param("reconciliationState") String reconciliationState,
                            @Param("failureReason") String failureReason);

    @Update("""
    UPDATE order_stripe_refund_attempts
    SET reconciliation_state = #{newState}
    WHERE id = #{attemptId}
      AND reconciliation_state = #{expectedState}
    """)
    int updateAttemptReconciliationState(@Param("attemptId") Long attemptId,
                                         @Param("expectedState") String expectedState,
                                         @Param("newState") String newState);

    @Update("""
    UPDATE order_stripe_refund_attempts
    SET reconciliation_state = #{nextState},
        retry_count = #{retryCount},
        next_retry_at = #{nextRetryAt,jdbcType=TIMESTAMP}
    WHERE id = #{attemptId}
      AND reconciliation_state = #{expectedState}
    """)
    int updateAttemptRetrySchedule(@Param("attemptId") Long attemptId,
                                   @Param("expectedState") String expectedState,
                                   @Param("nextState") String nextState,
                                   @Param("retryCount") int retryCount,
                                   @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("""
    UPDATE order_stripe_refund_attempts
    SET reconciliation_state = 'REVIEW',
        next_retry_at = NULL
    WHERE id = #{attemptId}
      AND idempotency_key = #{idempotencyKey}
      AND reconciliation_state = 'PENDING'
      AND refund_id IS NULL
    """)
    int markAttemptReconciliationReview(@Param("attemptId") Long attemptId,
                                       @Param("idempotencyKey") String idempotencyKey);

    /** 按主表业务状态和最新 Attempt 状态，挑选需要恢复或评估新 Attempt 的退款。 */
    @Select("""
            SELECT a.* FROM order_stripe_refund_attempts a
            JOIN order_stripe_refunds r ON r.order_id = a.order_id
            WHERE r.status = 'PENDING'
              AND NOT EXISTS (SELECT 1 FROM order_stripe_refund_attempts newer
                              WHERE newer.order_id = a.order_id AND newer.id > a.id)
              AND ((a.reconciliation_state = 'PENDING' AND (a.next_retry_at IS NULL OR a.next_retry_at <= NOW()))
                   OR (a.reconciliation_state = 'RECONCILED' AND a.stripe_status IN ('failed', 'canceled')))
            ORDER BY a.next_retry_at, a.create_time, a.id LIMIT #{limit}
            """)
    List<OrderStripeRefundAttempt> selectPendingRetryList(@Param("limit") int limit);

    /** 仅巡检仍未成功业务的最新失败尝试，成功的新 Attempt 不会被旧失败占住扫描名额。 */
    @Select("""
            SELECT a.* FROM order_stripe_refund_attempts a
            JOIN order_stripe_refunds r ON r.order_id = a.order_id
            WHERE a.stripe_status = 'failed' AND r.status IN ('PENDING', 'REVIEW')
              AND NOT EXISTS (SELECT 1 FROM order_stripe_refund_attempts newer
                              WHERE newer.order_id = a.order_id AND newer.id > a.id)
            ORDER BY a.update_time, a.order_id, a.id LIMIT #{limit}
            """)
    List<OrderStripeRefundAttempt> selectFailedAttempts(@Param("limit") int limit);
}
