package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Cart.Service.CartCommandService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderTransactionExecutor {
    private final OrderItemService orderItemService;
    private final OrderConvertor orderConvertor;
    private final OrderMapper orderMapper;
    private final CartCommandService cartCommandService;

    @Transactional(rollbackFor = Exception.class)
    public Order executeOrderCreation(OrderDataContext orderDataContext, CreateOrderDTO createOrderDTO,
                                      Long userId, String requestHash) {
        Order order;
        try {
            order = new Order();
            order.setUserId(userId);
            order.setRequestId(createOrderDTO.getRequestId());
            order.setOrderNo(createOrderNo());
            order.setMerchantId(orderDataContext.getMerchant().getId());
            order.setMerchantName(orderDataContext.getMerchant().getMerchantName());
            order.setTotalAmount(orderDataContext.getTotalAmount());
            order.setOriginalAmount(orderDataContext.getTotalAmount());
            order.setDiscountAmount(BigDecimal.ZERO); // NOTE: 留作后续扩展

            orderConvertor.toOrder(createOrderDTO, order);
            order.setStatus(OrderStatusEnum.WAIT_PAY.getCode());

            order.setRequestHash(requestHash);

            LocalDateTime createTime = LocalDateTime.now();
            order.setCreateTime(createTime);
            order.setTimeoutCancelAvailableAt(createTime.plusMinutes(30));


            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            Order commitOrder = orderMapper.selectOne(
                    Wrappers.<Order>lambdaQuery().
                            eq(Order::getUserId, userId).
                            eq(Order::getRequestId, createOrderDTO.getRequestId()));
            if (commitOrder != null) {
                if (!Objects.equals(commitOrder.getRequestHash(), requestHash)) {
                    throw new BusinessException(
                            ResultCodeEnum.PARAM_ERROR,
                            "同一 requestId 不能携带不同下单参数，请使用新的 requestId"
                    );
                }
                return commitOrder;
            }
            throw e;
        }

        cartCommandService.consumeCheckedOutItems(
                userId,
                orderDataContext.getAvailableItems()
        );

        orderItemService.decreaseStocksOrderedByProductId(orderDataContext.getAvailableItems());
        List<OrderItem> orderItems = orderItemService.buildOrderItems(order,
                orderDataContext.getAvailableItems(), orderDataContext.getProductMap());
        orderItemService.saveBatch(orderItems);
        return order;
    }
    private @NonNull String createOrderNo(){
        return "ORD" + System.currentTimeMillis() +
                UUID.randomUUID().toString().substring(0, 4);
    }
}
