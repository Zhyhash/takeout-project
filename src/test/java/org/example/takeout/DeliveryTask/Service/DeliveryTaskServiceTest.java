package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Rider.Entity.Rider;
import org.example.takeout.Rider.Enums.RiderStatusEnum;
import org.example.takeout.Rider.Mapper.RiderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryTaskServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DeliveryTask.class);
    }

    @Mock
    private DeliveryTaskMapper deliveryTaskMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private RiderMapper riderMapper;

    @InjectMocks
    private DeliveryTaskService deliveryTaskService;

    @AfterEach
    void clearRiderContext() {
        RiderContextHolder.clear();
    }

    @Test
    void disabledRiderCannotClaimTaskWithExistingToken() {
        mockDisabledRider();

        assertThrows(BusinessException.class, () -> deliveryTaskService.claimTask(501L));

        verifyNoInteractions(deliveryTaskMapper, orderMapper);
    }

    @Test
    void disabledRiderCannotCompleteTaskWithExistingToken() {
        mockDisabledRider();

        assertThrows(BusinessException.class, () -> deliveryTaskService.completeDelivery(501L));

        verifyNoInteractions(deliveryTaskMapper, orderMapper);
    }

    @Test
    void repeatedClaimIsIdempotentWhenTaskAndOrderAreBothDelivering() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectById(501L))
                .thenReturn(deliveryTask(DeliveryTaskEnums.DELIVERING.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERING.getCode()));

        assertDoesNotThrow(() -> deliveryTaskService.claimTask(501L));
    }

    @Test
    void repeatedClaimRejectsTaskAndOrderStateMismatch() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectById(501L))
                .thenReturn(deliveryTask(DeliveryTaskEnums.DELIVERING.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.READY.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.claimTask(501L));
    }

    @Test
    void claimRejectsPreExistingDeliveringOrderWhenTaskWasJustUpdated() {
        mockActiveRider();
        mockTaskUpdate(1);
        when(deliveryTaskMapper.selectById(501L))
                .thenReturn(deliveryTask(DeliveryTaskEnums.DELIVERING.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERING.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.claimTask(501L));
    }

    @Test
    void repeatedCompletionIsIdempotentWhenTaskAndOrderAreBothDelivered() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectById(501L))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERED.getCode()));

        assertDoesNotThrow(() -> deliveryTaskService.completeDelivery(501L));
    }

    @Test
    void repeatedCompletionRemainsIdempotentAfterUserHasFinishedOrder() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectById(501L))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.FINISHED.getCode()));

        assertDoesNotThrow(() -> deliveryTaskService.completeDelivery(501L));
    }

    @Test
    void repeatedCompletionRejectsTaskAndOrderStateMismatch() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectById(501L))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERING.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.completeDelivery(501L));
    }

    @Test
    void completionRejectsPreExistingDeliveredOrderWhenTaskWasJustUpdated() {
        mockActiveRider();
        mockTaskUpdate(1);
        when(deliveryTaskMapper.selectById(501L))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERED.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.completeDelivery(501L));
    }

    private void mockDisabledRider() {
        RiderContextHolder.setRiderId(301L);
        Rider rider = new Rider();
        rider.setId(301L);
        rider.setStatus(RiderStatusEnum.DISABLED.getCode());
        when(riderMapper.selectById(301L)).thenReturn(rider);
    }

    private void mockActiveRider() {
        RiderContextHolder.setRiderId(301L);
        Rider rider = new Rider();
        rider.setId(301L);
        rider.setStatus(RiderStatusEnum.NORMAL.getCode());
        when(riderMapper.selectById(301L)).thenReturn(rider);
    }

    private void mockTaskUpdateMiss() {
        mockTaskUpdate(0);
    }

    private void mockTaskUpdate(int rows) {
        when(deliveryTaskMapper.update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<DeliveryTask>>any())).thenReturn(rows);
    }

    private DeliveryTask deliveryTask(Integer status) {
        DeliveryTask task = new DeliveryTask();
        task.setId(501L);
        task.setOrderId(601L);
        task.setRiderId(301L);
        task.setStatus(status);
        return task;
    }

    private Order order(Integer status) {
        Order order = new Order();
        order.setId(601L);
        order.setStatus(status);
        return order;
    }
}
