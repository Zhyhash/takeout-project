package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderTransactionExecutor {
    @Autowired
    private OrderDomainService orderDomainService;
    @Autowired
    private OrderItemService orderItemService;
    @Autowired
    private OrderConvertor  orderConvertor;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private CartMapper cartMapper;

    @Transactional(rollbackFor = Exception.class)
    public Order executeOrderCreation(OrderDataContext orderDataContext, CreateOrderDTO createOrderDTO, Long userId) {
        Order order;
        try {
            order = new Order();
            order.setUserId(userId);
            order.setRequestId(createOrderDTO.getRequestId());
            order.setOrderNo(orderDomainService.createOrderNo());
            order.setMerchantId(orderDataContext.getMerchant().getId());
            order.setMerchantName(orderDataContext.getMerchant().getMerchantName());
            order.setTotalAmount(orderDataContext.getTotalAmount());
            order.setOriginalAmount(orderDataContext.getTotalAmount());
            order.setDiscountAmount(BigDecimal.ZERO); // NOTE: 留作后续扩展

            orderConvertor.toOrder(createOrderDTO, order);
            order.setStatus(OrderStatusEnum.WAIT_PAY.getCode());


            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            Order commitOrder = orderMapper.selectOne(
                    Wrappers.<Order>lambdaQuery().
                            eq(Order::getUserId, userId).
                            eq(Order::getRequestId, createOrderDTO.getRequestId()));
            if (commitOrder != null) {
                return commitOrder;
            }
            throw e;
        }

        int i = cartMapper.deleteByIds(orderDataContext.getAvailableItems().stream().map(CartItem::getId).toList());
        if (i != orderDataContext.getAvailableItems().size()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"购物车删除失败");
        }

        orderItemService.decreaseStocksOrderedByProductId(orderDataContext.getAvailableItems());
        List<OrderItem> orderItems = orderItemService.buildOrderItems(order,
                orderDataContext.getAvailableItems(), orderDataContext.getProductMap());
        orderItemService.saveBatch(orderItems);
        return order;
    }
}
