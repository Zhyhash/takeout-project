package org.example.takeout.Order.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Order.Entity.Order;


@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
