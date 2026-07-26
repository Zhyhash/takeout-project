package org.example.takeout.Cart.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;
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
}
