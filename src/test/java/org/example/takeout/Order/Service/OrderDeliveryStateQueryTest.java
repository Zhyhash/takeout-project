package org.example.takeout.Order.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDeliveryStateQueryTest {

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderService orderService;

    @AfterEach
    void assertionsOnlyReadOrderState() {
        verify(orderMapper).selectById(501L);
        verifyNoMoreInteractions(orderMapper);
    }

    @Test
    void deliveringAssertionAcceptsDeliveringOrder() {
        mockOrder(OrderStatusEnum.DELIVERING);

        assertDoesNotThrow(() -> orderService.assertDelivering(501L));
    }

    @Test
    void deliveringAssertionRejectsMissingOrder() {
        when(orderMapper.selectById(501L)).thenReturn(null);

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> orderService.assertDelivering(501L));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("订单不存在", failure.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatusEnum.class, names = "DELIVERING", mode = EnumSource.Mode.EXCLUDE)
    void deliveringAssertionRejectsOrdersOutsideDelivery(OrderStatusEnum status) {
        mockOrder(status);

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> orderService.assertDelivering(501L));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("订单未处于配送中状态，当前状态码：" + status.getCode(), failure.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatusEnum.class, names = {"DELIVERED", "FINISHED"})
    void completionAssertionAcceptsDeliveredAndFinishedOrders(OrderStatusEnum status) {
        mockOrder(status);

        assertDoesNotThrow(() -> orderService.assertOrderReachedDeliveryCompletion(501L));
    }

    @Test
    void completionAssertionRejectsMissingOrder() {
        when(orderMapper.selectById(501L)).thenReturn(null);

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> orderService.assertOrderReachedDeliveryCompletion(501L));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("订单不存在", failure.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatusEnum.class, names = {"DELIVERED", "FINISHED"}, mode = EnumSource.Mode.EXCLUDE)
    void completionAssertionRejectsOrdersThatHaveNotReachedDeliveryCompletion(OrderStatusEnum status) {
        mockOrder(status);

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> orderService.assertOrderReachedDeliveryCompletion(501L));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("订单未处于已送达或已完成状态，当前状态码：" + status.getCode(), failure.getMessage());
    }

    private void mockOrder(OrderStatusEnum status) {
        Order order = new Order();
        order.setId(501L);
        order.setStatus(status.getCode());
        when(orderMapper.selectById(501L)).thenReturn(order);
    }
}
