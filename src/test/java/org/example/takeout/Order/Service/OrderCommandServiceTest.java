package org.example.takeout.Order.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCommandServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderCommandService orderCommandService;

    @Test
    void acceptOrderChangesPaidOrderToPreparing() {
        when(orderMapper.updateOrderStatusToPreparing(
                501L,
                101L,
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.PREPARING.getCode())).thenReturn(1);

        assertDoesNotThrow(() -> orderCommandService.acceptOrderByMerchant(501L, 101L));

        verify(orderMapper, never()).selectById(501L);
    }

    @Test
    void repeatedAcceptIsIdempotentForSameMerchant() {
        mockAcceptUpdateMiss();
        when(orderMapper.selectById(501L))
                .thenReturn(order(101L, OrderStatusEnum.PREPARING.getCode()));

        assertDoesNotThrow(() -> orderCommandService.acceptOrderByMerchant(501L, 101L));
    }

    @Test
    void acceptOrderRejectsOrderOwnedByAnotherMerchant() {
        mockAcceptUpdateMiss();
        when(orderMapper.selectById(501L))
                .thenReturn(order(202L, OrderStatusEnum.PAID.getCode()));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> orderCommandService.acceptOrderByMerchant(501L, 101L));

        assertEquals("订单不存在或不属于当前商家", exception.getMessage());
    }

    @Test
    void markReadyReturnsChangedOrderAfterSuccessfulTransition() {
        Order order = order(101L, OrderStatusEnum.READY.getCode());
        when(orderMapper.updateOrderStatusToReady(
                501L,
                101L,
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode())).thenReturn(1);
        when(orderMapper.selectById(501L)).thenReturn(order);

        MarkReadyResult result = orderCommandService.markReadyByMerchant(501L, 101L);

        assertTrue(result.changed());
        assertSame(order, result.order());
    }

    @Test
    void repeatedMarkReadyReturnsUnchangedOrderForSameMerchant() {
        mockReadyUpdateMiss();
        Order order = order(101L, OrderStatusEnum.READY.getCode());
        when(orderMapper.selectById(501L)).thenReturn(order);

        MarkReadyResult result = orderCommandService.markReadyByMerchant(501L, 101L);

        assertFalse(result.changed());
        assertSame(order, result.order());
    }

    @Test
    void markReadyRejectsOrderInInvalidState() {
        mockReadyUpdateMiss();
        when(orderMapper.selectById(501L))
                .thenReturn(order(101L, OrderStatusEnum.PAID.getCode()));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> orderCommandService.markReadyByMerchant(501L, 101L));

        assertEquals(
                "订单当前状态为：" + OrderStatusEnum.PAID.getCode() + "，无法出餐",
                exception.getMessage());
    }

    private void mockAcceptUpdateMiss() {
        when(orderMapper.updateOrderStatusToPreparing(
                501L,
                101L,
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.PREPARING.getCode())).thenReturn(0);
    }

    private void mockReadyUpdateMiss() {
        when(orderMapper.updateOrderStatusToReady(
                501L,
                101L,
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode())).thenReturn(0);
    }

    private Order order(Long merchantId, Integer status) {
        Order order = new Order();
        order.setId(501L);
        order.setMerchantId(merchantId);
        order.setStatus(status);
        return order;
    }
}
