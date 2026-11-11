package org.example.takeout.Order.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Order.Entity.Order;

import java.time.LocalDateTime;
import java.util.List;


@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    @Select("SELECT * FROM orders WHERE id = #{orderId} FOR UPDATE")
    Order selectOrderForUpdate(Long orderId);

    @Select("SELECT COUNT(*) FROM order_stripe_payments WHERE order_id = #{orderId}")
    int countStripePaymentsForOrder(Long orderId);
    @Select("""
        SELECT id
        FROM orders
        WHERE status = #{status}
          AND timeout_cancel_available_at <= #{scanNow}
        ORDER BY timeout_cancel_available_at ASC, id ASC
        LIMIT #{limit}
        """)
    List<Long> selectTimeoutOrderIds(@Param("status") Integer status,
                                     @Param("scanNow") LocalDateTime scanNow,
                                     @Param("limit") Integer limit);

    @Update("""
        UPDATE orders
        SET status = #{newStatus},
            cancel_source = #{cancelSource}
        WHERE id = #{orderId}
          AND status = #{oldStatus}
          AND create_time <= #{expiredBefore}
        """)
    int updateTimeoutOrderToCancelled(@Param("orderId") Long orderId,
                                      @Param("oldStatus") Integer oldStatus,
                                      @Param("newStatus") Integer newStatus,
                                      @Param("expiredBefore") LocalDateTime expiredBefore,
                                      @Param("cancelSource") Integer cancelSource);

    @Update("""
        UPDATE orders
        SET timeout_cancel_available_at = DATE_ADD(NOW(), INTERVAL 30 MINUTE)
        WHERE id = #{orderId}
          AND status = #{status};
    """)
    int delayTimeoutCancellation(
            @Param("orderId") Long orderId,
            @Param("status") Integer status
    );



    @Update("""
        <script>
        UPDATE orders
        SET status = #{newStatus},
            cancel_source = #{cancelSource}
        WHERE id = #{orderId}
        AND user_id = #{userId}
        AND status IN
        <foreach collection="oldStatusList" item="status" open="(" separator="," close=")">
            #{status}
        </foreach>
        </script>
        """)
    int UpdateOrderStatusToCancel(@Param("orderId") Long orderId,
                                  @Param("userId") Long userId,
                                  @Param("oldStatusList") List<Integer> oldStatusList,
                                  @Param("newStatus") Integer newStatus,
                                  @Param("cancelSource") Integer cancelSource);

    @Update("""
        UPDATE orders
        SET status = #{newStatus}, payment_started_at = CURRENT_TIMESTAMP
        WHERE id = #{orderId}
        AND user_id = #{userId}
        AND status =#{oldStatus}
        AND create_time > #{expiredBefore}
    """)
    int updateOrderStatusToPaying(@Param("orderId") Long orderId,
                                  @Param("userId") Long userId,
                                  @Param("oldStatus") Integer oldStatus,
                                  @Param("newStatus") Integer newStatus,
                                  @Param("expiredBefore") LocalDateTime expiredBefore);

    @Update("""
    UPDATE orders
    SET status = #{refunding},
        merchant_seen = 1
    WHERE id = #{orderId}
      AND merchant_id = #{merchantId}
      AND status = #{paid}
    """)
    int updateOrderStatusToRefunding(@Param("orderId") Long orderId,@Param("refunding")Integer refunding,
                                     @Param("merchantId")Long merchantId,@Param("paid") Integer paid);

    /** Only used by the existing non-Stripe local simulation. */
    @Update("""
        UPDATE orders SET status = 2, pay_time = CURRENT_TIMESTAMP
        WHERE id = #{orderId} AND user_id = #{userId} AND status = 0
          AND create_time > #{expiredBefore}
        """)
    int completeSimulatedPayment(@Param("orderId") Long orderId, @Param("userId") Long userId,
                                 @Param("expiredBefore") LocalDateTime expiredBefore);

    @Update("""
        UPDATE orders SET status = 4, cancel_source = #{cancelSource}
        WHERE id = #{orderId} AND status = 1
        """)
    int cancelStripePaymentOrder(@Param("orderId") Long orderId, @Param("cancelSource") Integer cancelSource);

    @Update("""
        UPDATE orders
        SET status = #{newStatus},
            pay_time= #{payTime}
        WHERE id = #{orderId}
        AND user_id = #{userId}
        AND status =#{oldStatus}
    """)
    int updateOrderStatusToPaid(@Param("orderId") Long orderId,
                                @Param("userId") Long userId,
                                @Param("oldStatus") Integer oldStatus,
                                @Param("newStatus") Integer newStatus,
                                @Param("payTime") LocalDateTime payTime);


    @Update("""
        UPDATE orders
        SET status = #{newStatus},
            finish_time= #{finishTime}
        WHERE id = #{orderId}
        AND user_id = #{userId}
        AND status =#{oldStatus}
    """)
    int updateOrderStatusToFinished(@Param("orderId") Long orderId,
                                @Param("userId") Long userId,
                                @Param("oldStatus") Integer oldStatus,
                                @Param("newStatus") Integer newStatus,
                                @Param("finishTime") LocalDateTime finishTime);

    @Update("""
        UPDATE orders
        SET status = #{newStatus}
        WHERE id = #{orderId}
        AND merchant_id = #{merchantId}
        AND status =#{oldStatus}
    """)
    int updateOrderStatusToPreparing(@Param("orderId") Long orderId,
                                    @Param("merchantId") Long merchantId,
                                    @Param("oldStatus") Integer oldStatus,
                                    @Param("newStatus") Integer newStatus);

    @Update("""
        UPDATE orders
        SET status = #{newStatus}
        WHERE id = #{orderId}
        AND merchant_id = #{merchantId}
        AND status =#{oldStatus}
    """)
    int updateOrderStatusToReady(@Param("orderId") Long orderId,
                                 @Param("merchantId") Long merchantId,
                                 @Param("oldStatus") Integer oldStatus,
                                 @Param("newStatus") Integer newStatus);

    @Update("""
        UPDATE orders
        SET status = #{newStatus},
            cancel_source = #{cancelSource}
        WHERE id = #{orderId}
        AND merchant_id = #{merchantId}
        AND status = #{oldStatus}
    """)
    int updateOrderStatusToCancelledByMerchant(@Param("orderId") Long orderId,
                                               @Param("merchantId") Long merchantId,
                                               @Param("oldStatus") Integer oldStatus,
                                               @Param("newStatus") Integer newStatus,
                                               @Param("cancelSource") Integer cancelSource);

    @Update("""
        UPDATE orders
        SET status = #{newStatus}
        WHERE id = #{orderId}
        AND status =#{oldStatus}
    """)
    int updateOrderStatusToDelivering(@Param("orderId") Long orderId,
                                      @Param("oldStatus") Integer oldStatus,
                                      @Param("newStatus") Integer newStatus);

    @Update("""
        UPDATE orders
        SET status = #{newStatus}
        WHERE id = #{orderId}
        AND status =#{oldStatus}
    """)
    int updateOrderStatusToDelivered(@Param("orderId") Long orderId,
                                     @Param("oldStatus") Integer oldStatus,
                                     @Param("newStatus") Integer newStatus);

    @Select("""
        SELECT merchant_id
        FROM orders
        WHERE id = #{orderId}
    """)
    Long selectMerchantIdByOrderId(@Param("orderId") Long orderId);


    @Select("""
        SELECT id FROM orders
        WHERE merchant_id = #{merchantId}
          AND status = #{paidStatus}
          AND merchant_seen = #{unseen}
        ORDER BY create_time ASC, id ASC
    """)
    List<Long> selectMerchantUnseenOrders(
            @Param("merchantId") Long merchantId,
            @Param("paidStatus") Integer paidStatus,
            @Param("unseen") Boolean unseen);

    @Update("""
        UPDATE orders
        SET status = #{refunded}
        WHERE id = #{orderId}
        AND status =#{refunding}
    """)
    int updateOrderStatusToRefunded(@Param("orderId") Long orderId,
                                    @Param("refunding") Integer refunding,
                                    @Param("refunded") Integer refunded);
}
