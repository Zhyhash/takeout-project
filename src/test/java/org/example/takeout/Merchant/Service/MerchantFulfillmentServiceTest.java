package org.example.takeout.Merchant.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.DeliveryTask.Domain.DeliveryFeeCalculator;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantFulfillmentServiceTest {

    @Mock
    private MerchantMapper merchantMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private DeliveryTaskMapper deliveryTaskMapper;

    @Mock
    private DeliveryFeeCalculator deliveryFeeCalculator;

    @InjectMocks
    private MerchantService merchantService;

    @AfterEach
    void clearMerchantContext() {
        MerchantContextHolder.clear();
    }

    @Test
    void completePreparationIsIdempotentWhenOrderAndTaskAreBothWaitingForRider() {
        mockReadyOrder();
        DeliveryTask task = new DeliveryTask();
        task.setOrderId(501L);
        task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        when(deliveryTaskMapper.selectOne(any())).thenReturn(task);

        assertDoesNotThrow(() -> merchantService.completePreparation(501L));
    }

    @Test
    void completePreparationRejectsReadyOrderWithoutDeliveryTask() {
        mockReadyOrder();
        when(deliveryTaskMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> merchantService.completePreparation(501L));
    }

    @Test
    void completePreparationRejectsReadyOrderWithClaimedWaitingTask() {
        mockReadyOrder();
        DeliveryTask task = new DeliveryTask();
        task.setOrderId(501L);
        task.setRiderId(301L);
        task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        when(deliveryTaskMapper.selectOne(any())).thenReturn(task);

        assertThrows(BusinessException.class, () -> merchantService.completePreparation(501L));
    }

    @Test
    void completePreparationRejectsFailedTaskInsert() {
        MerchantContextHolder.setMerchantId(101L);
        when(orderMapper.updateOrderStatusToReady(
                501L,
                101L,
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode())).thenReturn(1);

        Order order = new Order();
        order.setId(501L);
        order.setMerchantId(101L);
        order.setStatus(OrderStatusEnum.READY.getCode());
        when(orderMapper.selectById(501L)).thenReturn(order);
        when(merchantMapper.selectById(101L)).thenReturn(new Merchant());
        when(deliveryFeeCalculator.calculateDeliveryReward()).thenReturn(BigDecimal.valueOf(5));
        when(deliveryTaskMapper.insert(any(DeliveryTask.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> merchantService.completePreparation(501L));

        ArgumentCaptor<DeliveryTask> taskCaptor = ArgumentCaptor.forClass(DeliveryTask.class);
        verify(deliveryTaskMapper).insert(taskCaptor.capture());
        assertEquals(0, BigDecimal.valueOf(5).compareTo(taskCaptor.getValue().getDeliveryReward()));
    }

    private void mockReadyOrder() {
        MerchantContextHolder.setMerchantId(101L);
        when(orderMapper.updateOrderStatusToReady(
                501L,
                101L,
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode())).thenReturn(0);

        Order order = new Order();
        order.setId(501L);
        order.setMerchantId(101L);
        order.setStatus(OrderStatusEnum.READY.getCode());
        when(orderMapper.selectById(501L)).thenReturn(order);
    }
}
