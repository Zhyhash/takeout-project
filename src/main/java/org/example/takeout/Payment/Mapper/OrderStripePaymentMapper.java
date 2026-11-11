package org.example.takeout.Payment.Mapper;

import org.apache.ibatis.annotations.*;
import org.example.takeout.Payment.Entity.OrderStripePayment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 只操作 order_stripe_payments：保存订单与 PaymentIntent 的关联、金额、状态和幂等键。
 * <p>恢复协调只依赖 {@code reconciliation_state} + {@code next_retry_at} + {@code retry_count}：
 * 没有跨实例租约列，占位由 {@link #claimAttempt} 把 {@code next_retry_at} 推到未来完成。</p>
 */
@Mapper
public interface OrderStripePaymentMapper {
    @Select("SELECT CURRENT_TIMESTAMP")
    LocalDateTime selectDatabaseTime();
    String COLUMNS = "order_id AS orderId, payment_intent_id AS paymentIntentId, "
            + "amount_minor AS amountMinor, currency, stripe_status AS stripeStatus, "
            + "idempotency_key AS idempotencyKey, create_time AS createTime, update_time AS updateTime, "
            + "reconciliation_state AS reconciliationState, "
            + "retry_count AS retryCount, next_retry_at AS nextRetryAt";

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE order_id = #{orderId}")
    OrderStripePayment selectPaymentByOrderId(Long orderId);

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE payment_intent_id = #{intentId}")
    OrderStripePayment selectPaymentByPaymentIntentId(String intentId);

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE order_id = #{orderId} FOR UPDATE")
    OrderStripePayment selectPaymentByOrderIdForUpdate(Long orderId);

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE payment_intent_id = #{intentId} FOR UPDATE")
    OrderStripePayment selectPaymentByPaymentIntentIdForUpdate(String intentId);

    @Insert("""
        INSERT INTO order_stripe_payments
            (order_id, amount_minor, currency, idempotency_key, create_time, update_time, next_retry_at)
        VALUES (#{orderId}, #{amountMinor}, #{currency}, #{idempotencyKey}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE order_id = order_id
        """)
    int insertPaymentIfAbsent(OrderStripePayment payment);

    @Update("""
        UPDATE order_stripe_payments
        SET payment_intent_id = #{intentId}, stripe_status = #{status}, update_time = CURRENT_TIMESTAMP
        WHERE order_id = #{orderId}
        """)
    int updatePaymentIntentAndStatus(@Param("orderId") Long orderId, @Param("intentId") String intentId,
                                     @Param("status") String status);

    @Select("""
        SELECT order_id FROM order_stripe_payments
        WHERE reconciliation_state = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP
        ORDER BY next_retry_at, order_id LIMIT #{limit}
        """)
    List<Long> selectRecoveryOrderIds(@Param("limit") int limit);

    /**
     * 占位式领取：把 {@code next_retry_at} 推到未来，同一时刻的第二个调用者会因
     * {@code next_retry_at <= now} 不成立而失败，因此不需要单独的租约列。
     */
    @Update("""
        UPDATE order_stripe_payments SET retry_count = retry_count + 1, next_retry_at = #{next}
        WHERE order_id = #{orderId} AND reconciliation_state = 'PENDING'
          AND next_retry_at <= #{now}
        """)
    int claimAttempt(@Param("orderId") Long orderId,
                     @Param("now") LocalDateTime now, @Param("next") LocalDateTime next);

    @Update("""
        UPDATE order_stripe_payments SET reconciliation_state = #{state},
            next_retry_at = #{next}, update_time = CURRENT_TIMESTAMP
        WHERE order_id = #{orderId}
        """)
    int updateReconciliation(@Param("orderId") Long orderId, @Param("state") String state,
                             @Param("next") LocalDateTime next);
}
