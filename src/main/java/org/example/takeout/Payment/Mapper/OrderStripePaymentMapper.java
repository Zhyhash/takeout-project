package org.example.takeout.Payment.Mapper;

import org.apache.ibatis.annotations.*;
import org.example.takeout.Payment.Entity.OrderStripePayment;

/** 只操作 order_stripe_payments：保存订单与 PaymentIntent 的关联、金额、状态和幂等键。 */
@Mapper
public interface OrderStripePaymentMapper {
    String COLUMNS = "order_id AS orderId, payment_intent_id AS paymentIntentId, "
            + "amount_minor AS amountMinor, currency, stripe_status AS stripeStatus, "
            + "idempotency_key AS idempotencyKey, create_time AS createTime, update_time AS updateTime";

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
            (order_id, amount_minor, currency, idempotency_key, create_time, update_time)
        VALUES (#{orderId}, #{amountMinor}, #{currency}, #{idempotencyKey}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
}
