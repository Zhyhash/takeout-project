package org.example.takeout.CartHeader.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.CartHeader.Entity.CartHeader;

@Mapper
public interface CartHeaderMapper extends BaseMapper<CartHeader> {
    @Update("""
        UPDATE cart_header
        SET merchant_id = #{merchantId}
        WHERE user_id = #{userId}
        """)
    int bindMerchant(@Param("userId") Long userId, @Param("merchantId") Long merchantId);

    @Update("""
        UPDATE cart_header
        SET merchant_id = NULL
        WHERE user_id = #{userId}
        """)
    int unbindMerchant(@Param("userId") Long userId);

    @Select("""
        SELECT user_id, merchant_id
        FROM cart_header
        WHERE user_id = #{userId}
        FOR UPDATE
""")
    CartHeader lockCartHeader(@Param("userId") Long userId) ;
}
