package org.example.takeout.Payment.Mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只操作 stripe_payment_events：记录已处理的 Webhook 事件，供重复事件去重。 */
@Mapper
public interface StripePaymentEventMapper {

    @Select("SELECT COUNT(*) FROM stripe_payment_events WHERE event_id = #{eventId}")
    int countEventsByEventId(String eventId);

    @Insert("""
        INSERT INTO stripe_payment_events (event_id, payment_intent_id, event_type, create_time)
        VALUES (#{eventId}, #{intentId}, #{eventType}, CURRENT_TIMESTAMP)
        """)
    int insertPaymentEvent(@Param("eventId") String eventId, @Param("intentId") String intentId,
                           @Param("eventType") String eventType);
}
