package org.example.takeout.Payment.Mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只操作 stripe_refund_events：记录已处理的退款 Webhook 事件，供重复事件去重。 */
@Mapper
public interface StripeRefundEventMapper {

    @Select("SELECT COUNT(*) FROM stripe_refund_events WHERE event_id = #{eventId}")
    int countEventsByEventId(@Param("eventId") String eventId);

    @Insert("""
        INSERT INTO stripe_refund_events (event_id, refund_id, event_type, create_time)
        VALUES (#{eventId}, #{refundId}, #{eventType}, CURRENT_TIMESTAMP)
        """)
    int insertRefundEvent(@Param("eventId") String eventId, @Param("refundId") String refundId,
                          @Param("eventType") String eventType);
}
