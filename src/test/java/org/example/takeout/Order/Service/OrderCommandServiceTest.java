package org.example.takeout.Order.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderCancelSourceEnum;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCommandServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemService orderItemService;

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

        assertEquals("订单不存在或无权操作该订单", exception.getMessage());
    }

    @Test
    void legacySimulatedOrderCanStillBeRejectedAndRestoreInventory() {
        OrderItem item = new OrderItem();
        when(orderMapper.selectOrderForUpdate(501L)).thenReturn(order(101L, 2));
        when(orderMapper.updateOrderStatusToCancelledByMerchant(501L, 101L, 2, 4, 2)).thenReturn(1);
        when(orderItemService.findOrderItemsByOrderId(501L)).thenReturn(List.of(item));
        orderCommandService.rejectOrderByMerchant(501L, 101L);
        verify(orderItemService).increaseStocksOrderedByProductId(List.of(item));
    }

    @Test
    void stripePaymentCannotReportFakeRefundEvenWhenStripeFeatureIsDisabled() {
        when(orderMapper.selectOrderForUpdate(501L)).thenReturn(order(101L, 2));
        when(orderMapper.countStripePaymentsForOrder(501L)).thenReturn(1);
        assertThrows(BusinessException.class, () -> orderCommandService.rejectOrderByMerchant(501L, 101L));
        verify(orderMapper, never()).updateOrderStatusToCancelledByMerchant(anyLong(), anyLong(), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(orderItemService);
    }

    @Test
    void repeatedRejectIsIdempotentForSameMerchant() {
        Order canceled = order(101L, 4);
        canceled.setCancelSource(2);
        when(orderMapper.selectOrderForUpdate(501L)).thenReturn(canceled);
        assertDoesNotThrow(() -> orderCommandService.rejectOrderByMerchant(501L, 101L));
        verifyNoInteractions(orderItemService);
    }

    @Test
    void rejectOrderRejectsAnotherMerchantAndPayingOrPreparingOrders() {
        when(orderMapper.selectOrderForUpdate(501L)).thenReturn(order(202L, 2));
        assertThrows(BusinessException.class, () -> orderCommandService.rejectOrderByMerchant(501L, 101L));
        when(orderMapper.selectOrderForUpdate(501L)).thenReturn(order(101L, 1));
        assertThrows(BusinessException.class, () -> orderCommandService.rejectOrderByMerchant(501L, 101L));
        when(orderMapper.selectOrderForUpdate(501L)).thenReturn(order(101L, 5));
        assertThrows(BusinessException.class, () -> orderCommandService.rejectOrderByMerchant(501L, 101L));
        verifyNoInteractions(orderItemService);
    }

    @Test
    void rejectOrderRejectsOrderWithoutDetails() {
        when(orderMapper.selectOrderForUpdate(501L)).thenReturn(order(101L, 2));
        when(orderMapper.updateOrderStatusToCancelledByMerchant(501L, 101L, 2, 4, 2)).thenReturn(1);
        when(orderItemService.findOrderItemsByOrderId(501L)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> orderCommandService.rejectOrderByMerchant(501L, 101L));
        verify(orderItemService, never()).increaseStocksOrderedByProductId(any());
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
                "订单未处于制作中状态，当前状态码：" + OrderStatusEnum.PAID.getCode(),
                exception.getMessage());
    }

    @Test
    void deliveryClaimChangesOnlyReadyOrderToDelivering() {
        when(orderMapper.updateOrderStatusToDelivering(501L,
                OrderStatusEnum.READY.getCode(), OrderStatusEnum.DELIVERING.getCode())).thenReturn(1);

        assertDoesNotThrow(() -> orderCommandService.updateOrderStatusToDelivering(501L));

        verify(orderMapper, never()).selectById(501L);
    }

    @Test
    void deliveryCompletionChangesOnlyDeliveringOrderToDelivered() {
        when(orderMapper.updateOrderStatusToDelivered(501L,
                OrderStatusEnum.DELIVERING.getCode(), OrderStatusEnum.DELIVERED.getCode())).thenReturn(1);

        assertDoesNotThrow(() -> orderCommandService.updateOrderStatusToDelivered(501L));

        verify(orderMapper, never()).selectById(501L);
    }

    @Test
    void deliveryClaimRejectsNonReadyOrder() {
        when(orderMapper.selectById(501L)).thenReturn(order(101L, OrderStatusEnum.PAID.getCode()));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderCommandService.updateOrderStatusToDelivering(501L));

        assertEquals("订单未处于待配送状态，当前状态码：" + OrderStatusEnum.PAID.getCode(), failure.getMessage());
    }

    @Test
    void deliveryCompletionRejectsOrderThatIsNotDelivering() {
        when(orderMapper.selectById(501L)).thenReturn(order(101L, OrderStatusEnum.READY.getCode()));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderCommandService.updateOrderStatusToDelivered(501L));

        assertEquals("订单未处于配送中状态，当前状态码：" + OrderStatusEnum.READY.getCode(), failure.getMessage());
    }

    @Test
    void deliveryCommandsRejectMissingOrder() {
        BusinessException claimFailure = assertThrows(BusinessException.class,
                () -> orderCommandService.updateOrderStatusToDelivering(501L));
        BusinessException completionFailure = assertThrows(BusinessException.class,
                () -> orderCommandService.updateOrderStatusToDelivered(501L));

        assertEquals("订单不存在", claimFailure.getMessage());
        assertEquals("订单不存在", completionFailure.getMessage());
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
