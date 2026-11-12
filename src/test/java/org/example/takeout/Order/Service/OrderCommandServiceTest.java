package org.example.takeout.Order.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Service.StripeRefundService;
import org.example.takeout.Payment.VO.RefundPreparation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCommandServiceTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemService orderItemService;

    @Mock
    private ObjectProvider<StripeRefundService> stripeRefundServiceProvider;

    @Mock
    private StripeRefundService stripeRefundService;

    @Mock
    private TaskExecutor taskExecutor;

    private OrderCommandService orderCommandService;

    @BeforeEach
    void setUp() {
        orderCommandService = new OrderCommandService(
                orderMapper,
                orderItemService,
                stripeRefundServiceProvider,
                taskExecutor);
    }

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
    void enabledStripeRejectionRestoresInventoryForNewRefund() {
        enableStripePayments();
        OrderItem item = new OrderItem();
        when(stripeRefundService.prepareRefund(501L, 101L))
                .thenReturn(new RefundPreparation(new OrderStripeRefund(), true));
        when(orderItemService.findOrderItemsByOrderId(501L)).thenReturn(List.of(item));

        TransactionSynchronizationManager.initSynchronization();
        try {
            orderCommandService.rejectOrderByMerchant(501L, 101L);
            verify(orderItemService).increaseStocksOrderedByProductId(List.of(item));
            verify(stripeRefundService).prepareRefund(501L, 101L);
            verifyNoInteractions(taskExecutor);

            List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCommit();

            org.mockito.ArgumentCaptor<Runnable> refundTask =
                    org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(taskExecutor).execute(refundTask.capture());
            refundTask.getValue().run();
            verify(stripeRefundService).refundRejectedOrder(501L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void stripeDisabledRejectFailsBeforeReadingOrMutatingOrderState() {
        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> orderCommandService.rejectOrderByMerchant(501L, 101L));

        assertEquals("Stripe 退款未启用，无法拒单", failure.getMessage());
        verifyNoInteractions(orderMapper, orderItemService, stripeRefundServiceProvider,
                stripeRefundService, taskExecutor);
    }

    @Test
    void repeatedRejectIsIdempotentForSameMerchant() {
        enableStripePayments();
        when(stripeRefundService.prepareRefund(501L, 101L))
                .thenReturn(new RefundPreparation(new OrderStripeRefund(), false));

        assertDoesNotThrow(() -> orderCommandService.rejectOrderByMerchant(501L, 101L));

        verify(stripeRefundService).prepareRefund(501L, 101L);
        verifyNoInteractions(orderItemService);
    }

    @Test
    void enabledRejectPropagatesRefundPreparationFailureWithoutRestoringInventory() {
        enableStripePayments();
        when(stripeRefundService.prepareRefund(501L, 101L))
                .thenThrow(new BusinessException(
                        ResultCodeEnum.BUSINESS_ERROR,
                        "订单状态不允许退款"));

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> orderCommandService.rejectOrderByMerchant(501L, 101L));

        assertEquals("订单状态不允许退款", failure.getMessage());
        verify(stripeRefundService).prepareRefund(501L, 101L);
        verifyNoInteractions(orderItemService);
    }

    @Test
    void rejectOrderRejectsOrderWithoutDetails() {
        enableStripePayments();
        when(stripeRefundService.prepareRefund(501L, 101L))
                .thenReturn(new RefundPreparation(new OrderStripeRefund(), true));
        when(orderItemService.findOrderItemsByOrderId(501L)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> orderCommandService.rejectOrderByMerchant(501L, 101L));
        verify(orderItemService, never()).increaseStocksOrderedByProductId(any());
    }

    // ------------------------------------------------------------------
    // 商家超时未接单自动取消（与拒单共用退款链路）
    // ------------------------------------------------------------------

    @Test
    void acceptOrderIsRejectedWhenTheTenMinuteWindowHasPassed() {
        mockAcceptUpdateMiss();
        when(orderMapper.selectById(501L)).thenReturn(order(101L, OrderStatusEnum.PAID.getCode()));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderCommandService.acceptOrderByMerchant(501L, 101L));

        assertEquals("订单已超过商家接单时限，将被系统自动取消并退款", failure.getMessage());
    }

    @Test
    void timeoutCancellationRestoresInventoryAndSubmitsRefundAfterCommit() {
        enableStripePayments();
        OrderItem item = new OrderItem();
        when(stripeRefundService.prepareTimeoutRefund(501L))
                .thenReturn(new RefundPreparation(new OrderStripeRefund(), true));
        when(orderItemService.findOrderItemsByOrderId(501L)).thenReturn(List.of(item));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(orderCommandService.cancelOrderByMerchantTimeout(501L));

            verify(stripeRefundService).prepareTimeoutRefund(501L);
            verify(orderItemService).increaseStocksOrderedByProductId(List.of(item));
            // Stripe 调用必须等到事务提交之后，不能在持锁事务里发起
            verifyNoInteractions(taskExecutor);

            java.util.List<org.springframework.transaction.support.TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCommit();

            org.mockito.ArgumentCaptor<Runnable> refundTask =
                    org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(taskExecutor).execute(refundTask.capture());
            refundTask.getValue().run();
            verify(stripeRefundService).refundRejectedOrder(501L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void repeatedTimeoutCancellationSkipsInventoryAndRefund() {
        enableStripePayments();
        when(stripeRefundService.prepareTimeoutRefund(501L))
                .thenReturn(new RefundPreparation(new OrderStripeRefund(), false));

        assertFalse(orderCommandService.cancelOrderByMerchantTimeout(501L));

        verify(stripeRefundService).prepareTimeoutRefund(501L);
        verify(stripeRefundService, never()).refundRejectedOrder(anyLong());
        verifyNoInteractions(orderItemService, taskExecutor);
    }

    @Test
    void timeoutCancellationFailsWhenStripeIsDisabled() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderCommandService.cancelOrderByMerchantTimeout(501L));

        assertEquals("Stripe 退款未启用，无法执行超时取消", failure.getMessage());
        verifyNoInteractions(stripeRefundServiceProvider, stripeRefundService, orderItemService, taskExecutor);
    }

    @Test
    void timeoutCancellationStopsWhenTheOrderIsNoLongerPayable() {
        enableStripePayments();
        when(stripeRefundService.prepareTimeoutRefund(501L))
                .thenThrow(new BusinessException(
                        ResultCodeEnum.BUSINESS_ERROR, "当前订单状态不允许商家拒单退款"));

        assertThrows(BusinessException.class,
                () -> orderCommandService.cancelOrderByMerchantTimeout(501L));

        verifyNoInteractions(orderItemService, taskExecutor);
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

    private void enableStripePayments() {
        ReflectionTestUtils.setField(orderCommandService, "stripePaymentsEnabled", true);
        when(stripeRefundServiceProvider.getObject()).thenReturn(stripeRefundService);
    }
}
