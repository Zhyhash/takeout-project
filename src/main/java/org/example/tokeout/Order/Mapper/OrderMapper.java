package org.example.tokeout.Order.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.tokeout.Order.Entity.Order;


@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
