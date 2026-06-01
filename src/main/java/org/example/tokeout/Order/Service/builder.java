package org.example.tokeout.Order.Service;

import org.example.tokeout.Order.Entity.Order;
import org.example.tokeout.Order.Entity.OrderItem;
import org.example.tokeout.Order.VO.CreateOrderVO;
import org.example.tokeout.Order.VO.OrderDetailVO;
import org.example.tokeout.Order.VO.OrderItemVO;
import org.example.tokeout.Order.VO.OrderVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//NOTE:构建类，构建对象（Order/OrderItem组装）
@Service
public class builder {
    @Autowired
    private domain domain;
    public CreateOrderVO toCreateOrderVO(Order order) {
        CreateOrderVO vo = new CreateOrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderId(order.getId());
        return vo;
    }

    public OrderDetailVO toOrderDetailVO(Order order,List<OrderItem> orderItems) {
        OrderDetailVO orderDetailVO = new OrderDetailVO();
        if (!CollectionUtils.isEmpty(orderItems)){
            List<OrderItemVO> itemVOs = orderItems.stream().map(item -> {
                OrderItemVO itemVO = new OrderItemVO();
                BeanUtils.copyProperties(item, itemVO);
                return itemVO;
            }).collect(Collectors.toList());

            // 设置商品列表
            orderDetailVO.setItems(itemVOs);

        }else {
            orderDetailVO.setItems(Collections.emptyList());
        }
        return orderDetailVO;
    }

    public OrderVO toOrderVO(Order order, Map<Long, List<OrderItem>> itemsMap) {
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        List<OrderItem> items = itemsMap.getOrDefault(order.getId(), Collections.emptyList());
        orderVO.setProductSummary(domain.buildProductSummary(items));
        return orderVO;
    }
}
