package org.example.tokeout.Order.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.tokeout.Order.Entity.OrderItem;


@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
