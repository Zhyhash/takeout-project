package org.example.tokeout.Cart.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.tokeout.Cart.Entity.CartItem;

@Mapper
public interface CartMapper extends BaseMapper<CartItem> {
}
