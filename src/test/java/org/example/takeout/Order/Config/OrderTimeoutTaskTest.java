package org.example.takeout.Order.Config;

import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutTaskTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderService orderService;
    @InjectMocks
    private OrderTimeoutTask orderTimeoutTask;

    @Test
    void shouldContinueWithRemainingOrdersWhenOneCancellationFails() {
        when(orderMapper.selectTimeoutOrderIds(
                eq(OrderStatusEnum.WAIT_PAY.getCode()),
                any(LocalDateTime.class),
                eq(100)
        )).thenReturn(List.of(1L, 2L));
        when(orderService.cancelTimeoutOrder(eq(1L), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("restore stock failed"));
        when(orderService.cancelTimeoutOrder(eq(2L), any(LocalDateTime.class)))
                .thenReturn(true);

        assertDoesNotThrow(orderTimeoutTask::cancelTimeoutOrders);
        verify(orderService).cancelTimeoutOrder(eq(2L), any(LocalDateTime.class));
    }
}
