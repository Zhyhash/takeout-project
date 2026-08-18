package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Product.Service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutCancellationTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductService productService;
    @InjectMocks
    private OrderService orderService;

    @Test
    void shouldCancelExpiredWaitingOrderAndRestoreStock() {
        Long orderId = 1L;
        Long productId = 10L;
        LocalDateTime expiredBefore = LocalDateTime.now().minusMinutes(30);

        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductId(productId);
        item.setQuantity(2);

        when(orderMapper.updateTimeoutOrderToCancelled(
                orderId,
                OrderStatusEnum.WAIT_PAY.getCode(),
                OrderStatusEnum.CANCELLED.getCode(),
                expiredBefore
        )).thenReturn(1);
        when(orderItemMapper.selectList(ArgumentMatchers.<Wrapper<OrderItem>>any())).thenReturn(List.of(item));
        assertTrue(orderService.cancelTimeoutOrder(orderId, expiredBefore));
        verify(productService).increaseStock(productId, 2);
    }

    @Test
    void shouldNotRestoreStockWhenPaymentWinsTheStatusCompetition() {
        Long orderId = 1L;
        LocalDateTime expiredBefore = LocalDateTime.now().minusMinutes(30);

        when(orderMapper.updateTimeoutOrderToCancelled(
                eq(orderId),
                eq(OrderStatusEnum.WAIT_PAY.getCode()),
                eq(OrderStatusEnum.CANCELLED.getCode()),
                eq(expiredBefore)
        )).thenReturn(0);

        assertFalse(orderService.cancelTimeoutOrder(orderId, expiredBefore));
        verify(orderItemMapper, never()).selectList(ArgumentMatchers.<Wrapper<OrderItem>>any());
        verify(productService, never()).increaseStock(any(), any());
    }
}
