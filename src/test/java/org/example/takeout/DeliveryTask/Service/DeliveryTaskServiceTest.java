package org.example.takeout.DeliveryTask.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.DeliveryTask.Domain.DeliveryFeeCalculator;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.Merchant.Entity.Merchant;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

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

    @Mock
    private DeliveryFeeCalculator deliveryFeeCalculator;

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
        when(deliveryTaskMapper.selectOne(any()))
                .thenReturn(deliveryTask(DeliveryTaskEnums.DELIVERING.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERING.getCode()));

        assertDoesNotThrow(() -> deliveryTaskService.claimTask(501L));
    }

    @Test
    void repeatedClaimRejectsTaskAndOrderStateMismatch() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectOne(any()))
                .thenReturn(deliveryTask(DeliveryTaskEnums.DELIVERING.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.READY.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.claimTask(501L));
    }

    @Test
    void claimRejectsPreExistingDeliveringOrderWhenTaskWasJustUpdated() {
        mockActiveRider();
        mockTaskUpdate(1);
        when(deliveryTaskMapper.selectOne(any()))
                .thenReturn(deliveryTask(DeliveryTaskEnums.DELIVERING.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERING.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.claimTask(501L));
    }

    @Test
    void repeatedCompletionIsIdempotentWhenTaskAndOrderAreBothDelivered() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectOne(any()))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERED.getCode()));

        assertDoesNotThrow(() -> deliveryTaskService.completeDelivery(501L));
    }

    @Test
    void repeatedCompletionRemainsIdempotentAfterUserHasFinishedOrder() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectOne(any()))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.FINISHED.getCode()));

        assertDoesNotThrow(() -> deliveryTaskService.completeDelivery(501L));
    }

    @Test
    void repeatedCompletionRejectsTaskAndOrderStateMismatch() {
        mockActiveRider();
        mockTaskUpdateMiss();
        when(deliveryTaskMapper.selectOne(any()))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERING.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.completeDelivery(501L));
    }

    @Test
    void completionRejectsPreExistingDeliveredOrderWhenTaskWasJustUpdated() {
        mockActiveRider();
        mockTaskUpdate(1);
        when(deliveryTaskMapper.selectOne(any()))
                .thenReturn(deliveryTask(DeliveryTaskEnums.COMPLETED.getCode()));
        when(orderMapper.selectById(601L))
                .thenReturn(order(OrderStatusEnum.DELIVERED.getCode()));

        assertThrows(BusinessException.class, () -> deliveryTaskService.completeDelivery(501L));
    }

    @Test
    void waitingTaskAssertionAcceptsUnclaimedWaitingTask() {
        DeliveryTask task = new DeliveryTask();
        task.setOrderId(501L);
        task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        when(deliveryTaskMapper.selectOne(any())).thenReturn(task);

        assertDoesNotThrow(() -> deliveryTaskService.assertWaitingDeliveryTask(501L));
    }

    @Test
    void waitingTaskAssertionRejectsMissingTask() {
        when(deliveryTaskMapper.selectOne(any())).thenReturn(null);

        assertThrows(
                BusinessException.class,
                () -> deliveryTaskService.assertWaitingDeliveryTask(501L));
    }

    @Test
    void waitingTaskAssertionRejectsClaimedTask() {
        DeliveryTask task = new DeliveryTask();
        task.setOrderId(501L);
        task.setRiderId(301L);
        task.setStatus(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        when(deliveryTaskMapper.selectOne(any())).thenReturn(task);

        assertThrows(
                BusinessException.class,
                () -> deliveryTaskService.assertWaitingDeliveryTask(501L));
    }

    @Test
    void taskCreationCopiesOrderAndMerchantSnapshot() {
        Order order = new Order();
        order.setId(501L);
        order.setMerchantName("测试商家");
        order.setReceiverName("收货人");
        order.setReceiverPhone("13800138000");
        order.setReceiverAddress("测试地址");
        Merchant merchant = new Merchant();
        merchant.setAddress("商家地址");
        merchant.setPhone("13900139000");
        when(deliveryFeeCalculator.calculateDeliveryReward())
                .thenReturn(BigDecimal.valueOf(5));
        when(deliveryTaskMapper.insert(any(DeliveryTask.class))).thenReturn(1);

        deliveryTaskService.createWaitingTask(order, merchant);

        ArgumentCaptor<DeliveryTask> captor = ArgumentCaptor.forClass(DeliveryTask.class);
        verify(deliveryTaskMapper).insert(captor.capture());
        DeliveryTask task = captor.getValue();
        assertEquals(501L, task.getOrderId());
        assertEquals("测试商家", task.getMerchantName());
        assertEquals("收货人", task.getReceiverName());
        assertEquals("13800138000", task.getReceiverPhone());
        assertEquals("测试地址", task.getReceiverAddress());
        assertEquals("商家地址", task.getMerchantAddress());
        assertEquals("13900139000", task.getMerchantPhone());
        assertEquals(0, BigDecimal.valueOf(5).compareTo(task.getDeliveryReward()));
        assertEquals(DeliveryTaskEnums.WAIT_ASSIGN.getCode(), task.getStatus());
    }

    @Test
    void taskCreationRejectsFailedInsert() {
        when(deliveryFeeCalculator.calculateDeliveryReward())
                .thenReturn(BigDecimal.valueOf(5));
        when(deliveryTaskMapper.insert(any(DeliveryTask.class))).thenReturn(0);

        assertThrows(
                BusinessException.class,
                () -> deliveryTaskService.createWaitingTask(new Order(), new Merchant()));
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
