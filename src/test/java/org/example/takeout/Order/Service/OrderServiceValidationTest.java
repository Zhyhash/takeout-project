package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Service.cartDomainService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceValidationTest {

    @Mock
    private OrderDomainService orderDomainService;
    @Mock
    private OrderTransactionExecutor orderTransactionExecutor;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderVOBuilder orderVOBuilder;
    @Mock
    private cartDomainService cartDomainService;
    @InjectMocks
    private OrderService orderService;

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void createOrderRejectsCartWhenAnyItemIsUnavailable() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-with-unavailable-item");

        CartItem availableItem = cartItem(1L);
        CartItem unavailableItem = cartItem(2L);
        CartAvailableResult result = new CartAvailableResult();
        result.setAllItems(List.of(availableItem, unavailableItem));
        result.setAvailableItems(List.of(availableItem));

        when(orderMapper.selectOne(any())).thenReturn(null);
        when(cartDomainService.getAvailableCartItems(21L)).thenReturn(result);

        assertThrows(BusinessException.class, () -> orderService.createOrder(dto));
        verify(orderTransactionExecutor, never()).executeOrderCreation(any(), any(), any());
    }

    @Test
    void cancelOrderRejectsOrderWithoutDetails() {
        UserContextHolder.setUserId(22L);
        when(orderMapper.UpdateOrderStatusToCancel(
                eq(501L),
                eq(22L),
                anyList(),
                eq(OrderStatusEnum.CANCELLED.getCode())
        )).thenReturn(1);
        when(orderItemMapper.selectList(ArgumentMatchers.<Wrapper<OrderItem>>any())).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> orderService.cancelOrder(501L));
    }

    private CartItem cartItem(Long id) {
        CartItem item = new CartItem();
        item.setId(id);
        return item;
    }
}
