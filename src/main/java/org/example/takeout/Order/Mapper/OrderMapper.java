package org.example.takeout.Order.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Order.Entity.Order;

import java.time.LocalDateTime;
import java.util.List;


@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    @Update("""
        <script>
        UPDATE orders
        SET status = #{newStatus}
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
                                  @Param("newStatus") Integer newStatus);

    @Update("""
        UPDATE orders
        SET status = #{newStatus}
        WHERE id = #{orderId}
        AND user_id = #{userId}
        AND status =#{oldStatus}
    """)
    int updateOrderStatusToPaying(@Param("orderId") Long orderId,
                                  @Param("userId") Long userId,
                                  @Param("oldStatus") Integer oldStatus,
                                  @Param("newStatus") Integer newStatus);

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
}
