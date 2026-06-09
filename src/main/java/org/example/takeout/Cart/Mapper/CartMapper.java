package org.example.takeout.Cart.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Cart.Entity.CartItem;

@Mapper
public interface CartMapper extends BaseMapper<CartItem> {
}
