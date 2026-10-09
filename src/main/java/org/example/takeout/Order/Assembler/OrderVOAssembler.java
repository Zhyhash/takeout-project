package org.example.takeout.Order.Assembler;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Support.OrderProductSummaryBuilder;
import org.example.takeout.Order.VO.OrderDetailVO;
import org.example.takeout.Order.VO.OrderItemVO;
import org.example.takeout.Order.VO.OrderVO;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//NOTE:构建类，构建对象（Order/OrderItem组装），事实上，这个类可能是多余的，就像我下面写的t odo一样
@Service
@RequiredArgsConstructor
public class OrderVOAssembler {
    private final OrderConvertor orderConvertor;
    private final OrderProductSummaryBuilder orderProductSummaryBuilder;

    public OrderDetailVO toOrderDetailVO(Order order,List<OrderItem> orderItems) {
        OrderDetailVO orderDetailVO = orderConvertor.toOrderDetailVO(order);
        //TODO:记得重构喵，可以把这个类直接清空的重构：
        // 只要定义了这个（mapper层），MapStruct 会自动在底层帮你写好循环和非空判断
        // List<OrderItemVO> toOrderItemVOList(List<OrderItem> orderItems);
        if (!CollectionUtils.isEmpty(orderItems)){
            List<OrderItemVO> itemVOs = orderItems.stream().
                    map(orderConvertor::toOrderItemVO).
                    collect(Collectors.toList());
            // 设置商品列表
            orderDetailVO.setItems(itemVOs);
        }else {
            orderDetailVO.setItems(Collections.emptyList());
        }
        return orderDetailVO;
    }

    public OrderVO toOrderVO(Order order, Map<Long, List<OrderItem>> itemsMap) {
        OrderVO orderVO = orderConvertor.toOrderVO(order);
        List<OrderItem> items = itemsMap.getOrDefault(order.getId(), Collections.emptyList());
        orderVO.setProductSummary(orderProductSummaryBuilder.buildProductSummary(items));
        return orderVO;
    }
}
