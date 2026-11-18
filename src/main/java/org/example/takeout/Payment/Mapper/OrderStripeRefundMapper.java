package org.example.takeout.Payment.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Payment.Entity.OrderStripeRefund;

@Mapper
public interface OrderStripeRefundMapper extends BaseMapper<OrderStripeRefund> {
    @Select("""
    SELECT * FROM order_stripe_refunds WHERE order_id = #{orderId} FOR UPDATE
    """)
    OrderStripeRefund selectByOrderIdForUpdate(@Param("orderId") Long orderId);

    /** 只在业务状态仍符合读取时的预期值时推进主退款状态。 */
    @Update("""
    UPDATE order_stripe_refunds
    SET status = #{newStatus}
    WHERE order_id = #{orderId}
      AND status = #{expectedStatus}
    """)
    int updateRefundBusinessStatus(@Param("orderId") Long orderId,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("newStatus") String newStatus);
}
