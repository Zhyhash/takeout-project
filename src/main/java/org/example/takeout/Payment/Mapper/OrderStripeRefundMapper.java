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

    /**
     * 写入退款单号与最新远端状态。
     * <p>{@code WHERE} 条件限定"仍未取得终态"：调用方虽然已经用 {@code FOR UPDATE} 持有行锁，
     * 但数据库层再拦一次，可以保证已落库的终态不会被任何路径覆盖回 pending。</p>
     */
    @Update("""
    UPDATE order_stripe_refunds
    SET refund_id = #{refundId},
        stripe_status = #{newStatus},
        reconciliation_state = #{reconciliationState}
    WHERE order_id = #{orderId}
      AND (stripe_status IS NULL OR stripe_status = 'pending')
    """)
    int updateStatus(@Param("orderId") Long orderId,
                     @Param("refundId") String refundId,
                     @Param("newStatus") String newStatus,
                     @Param("reconciliationState") String reconciliationState);

    /** 只改本地对账状态，不动远端退款状态；用于冲突转人工这类"不改事实、只改处理方式"的场景。 */
    @Update("""
    UPDATE order_stripe_refunds
    SET reconciliation_state = #{reconciliationState}
    WHERE order_id = #{orderId}
    """)
    int updateReconciliationState(@Param("orderId") Long orderId,
                                  @Param("reconciliationState") String reconciliationState);

    @Select("""
    SELECT * FROM order_stripe_refunds
    WHERE order_id=#{orderId}
""")
    OrderStripeRefund selectPaymentByOrderId(@Param("orderId") Long orderId);
}
