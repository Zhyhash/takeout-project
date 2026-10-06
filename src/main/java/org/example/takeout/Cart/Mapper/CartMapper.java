package org.example.takeout.Cart.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.apache.ibatis.annotations.*;
import org.example.takeout.Cart.Entity.CartItem;

@Mapper
public interface CartMapper extends BaseMapper<CartItem> {
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("""
        INSERT INTO cart (
            user_id, product_id, merchant_id, quantity,
            product_name, product_image, price, version
        )
        VALUES (
            #{userId},
            #{productId},
            #{merchantId},
            1,
            #{productName},
            #{productImage},
            #{price},
            0
        )
        ON DUPLICATE KEY UPDATE
            id = LAST_INSERT_ID(id),
            quantity = quantity + 1,
            version = version+1,
            update_time = CURRENT_TIMESTAMP
        """)
    int addOrIncrease(CartItem cartItem);

    @Update("""
        UPDATE cart
        SET quantity = quantity - #{consumeQuantity},
            version = version + 1,
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{id}
          AND user_id = #{userId}
          AND quantity >= #{consumeQuantity};
    """)
    int consumeQuantity(@Param("userId") Long userId,@Param("id") Long id, @Param("consumeQuantity") Integer consumeQuantity);

    @Delete("""
        DELETE FROM cart
        WHERE id = #{id}
          AND user_id = #{userId}
          AND quantity = 0
    """)
    void deleteIfEmpty(Long userId, Long id);

    @Select(
            """
    
        SELECT *
        FROM cart
        WHERE user_id = #{userId}
          AND product_id = #{productId}
        FOR UPDATE;
    """
    )
    CartItem selectByUserIdAndProductIdForUpdate(Long userId, @NotNull(message = "商品id不能为空") @Positive(message = "商品id必须为正数") Long productId);

    @Select("""
    SELECT EXISTS (
        SELECT 1
        FROM cart
        WHERE user_id = #{userId}
    )
    """)
    boolean existsByUserId(@Param("userId") Long userId);


}
