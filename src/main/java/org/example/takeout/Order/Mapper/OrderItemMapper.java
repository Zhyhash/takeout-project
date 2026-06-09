package org.example.takeout.Order.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Order.Entity.OrderItem;


@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
