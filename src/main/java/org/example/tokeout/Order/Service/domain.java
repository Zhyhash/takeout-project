package org.example.tokeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.tokeout.Common.Exception.BusinessException;
import org.example.tokeout.Order.Entity.Order;
import org.example.tokeout.Order.Entity.OrderItem;
import org.example.tokeout.Order.Mapper.OrderMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

//NOTE:校验类
@Service
public class domain {
    //NOTE:全局方法：数据库校验抽取
    @Autowired
    private OrderMapper orderMapper;
    public Order getAndCheckOrder(Long orderId, Long userId, Integer expectedStatus) {
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .eq(expectedStatus != null, Order::getStatus, expectedStatus));
        if (order == null) {
            throw new BusinessException("订单不存在或状态不符");
        }
        return order;
    }
    //NOTE:聚合函数，负责拿到productSummary商品简介
    public String buildProductSummary(List<OrderItem> items){
        //从items里面拿到商品
        if (items==null||items.isEmpty())
            return "";
        int sum = items.stream().mapToInt(OrderItem::getQuantity).sum();
        String firstProductName = items.get(0).getProductName();
        if (sum==1)
            return firstProductName;
        return String.format("%s 等 %d 件商品", firstProductName, sum);
    }
    //NOTE:制造orderNo方法
    public @NonNull String createOrderNo(){
        return "ORD" + System.currentTimeMillis() +
                UUID.randomUUID().toString().substring(0, 4);
    }
}
