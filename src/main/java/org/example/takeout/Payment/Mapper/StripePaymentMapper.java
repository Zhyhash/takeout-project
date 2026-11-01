package org.example.takeout.Payment.Mapper;

import org.apache.ibatis.annotations.*;
import org.example.takeout.Payment.Entity.OrderStripePayment;

@Mapper
public interface StripePaymentMapper {
    String COLUMNS = "order_id AS orderId, payment_intent_id AS paymentIntentId, "
            + "amount_minor AS amountMinor, currency, stripe_status AS stripeStatus, "
            + "idempotency_key AS idempotencyKey, create_time AS createTime, update_time AS updateTime";

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE order_id = #{orderId}")
    OrderStripePayment findByOrderId(Long orderId);

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE payment_intent_id = #{intentId}")
    OrderStripePayment findByIntentId(String intentId);

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE order_id = #{orderId} FOR UPDATE")
    OrderStripePayment findByOrderIdForUpdate(Long orderId);

    @Select("SELECT " + COLUMNS + " FROM order_stripe_payments WHERE payment_intent_id = #{intentId} FOR UPDATE")
    OrderStripePayment findByIntentIdForUpdate(String intentId);

    @Insert("""
        INSERT INTO order_stripe_payments
            (order_id, amount_minor, currency, idempotency_key, create_time, update_time)
        VALUES (#{orderId}, #{amountMinor}, #{currency}, #{idempotencyKey}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE order_id = order_id
        """)
    int insertIfAbsent(OrderStripePayment payment);

    @Update("""
        UPDATE order_stripe_payments
        SET payment_intent_id = #{intentId}, stripe_status = #{status}, update_time = CURRENT_TIMESTAMP
        WHERE order_id = #{orderId}
        """)
    int updateSnapshot(@Param("orderId") Long orderId, @Param("intentId") String intentId,
                       @Param("status") String status);

    @Select("SELECT COUNT(*) FROM stripe_payment_events WHERE event_id = #{eventId}")
    int eventExists(String eventId);

    @Insert("""
        INSERT INTO stripe_payment_events (event_id, payment_intent_id, event_type, create_time)
        VALUES (#{eventId}, #{intentId}, #{eventType}, CURRENT_TIMESTAMP)
        """)
    int insertEvent(@Param("eventId") String eventId, @Param("intentId") String intentId,
                    @Param("eventType") String eventType);
}
