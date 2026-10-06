package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Cart.Service.cartDomainService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceIdempotencyTest {
    private static final Long USER_ID = 21L;

    @Mock private OrderDomainService orderDomainService;
    @Mock private OrderTransactionExecutor orderTransactionExecutor;
    @Mock private OrderItemService orderItemService;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderVOBuilder orderVOBuilder;
    @Mock private cartDomainService cartDomainService;
    @Spy private OrderRequestFingerprint fingerprint = new OrderRequestFingerprint();
    @InjectMocks private OrderService orderService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void changedParametersAreRejectedBeforeCartOrStockOperations() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO original = TestDataFactory.createOrderDTO();
        Order existing = existingOrder(original);
        when(orderMapper.selectOne(any())).thenReturn(existing);
        CreateOrderDTO changed = TestDataFactory.createOrderDTO();
        changed.setRequestId(original.getRequestId());
        changed.setRemark("不要辣椒");

        BusinessException exception = assertThrows(BusinessException.class, () -> orderService.createOrder(changed));

        assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        assertEquals("同一 requestId 不能携带不同下单参数，请使用新的 requestId", exception.getMessage());
        assertLookupUsesOriginalUniqueKey(original.getRequestId());
        verifyNoInteractions(cartDomainService, orderDomainService, orderTransactionExecutor,
                orderItemService, orderItemMapper, orderVOBuilder);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void sameParametersReturnExistingOrderWithoutUsingNewCart() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO original = TestDataFactory.createOrderDTO();
        Order existing = existingOrder(original);
        CreateOrderVO expected = new CreateOrderVO();
        expected.setOrderId(existing.getId());
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(orderVOBuilder.toCreateOrderVO(existing)).thenReturn(expected);
        CreateOrderDTO retry = TestDataFactory.createOrderDTO();
        retry.setRequestId(original.getRequestId());

        assertSame(expected, orderService.createOrder(retry));

        assertLookupUsesOriginalUniqueKey(original.getRequestId());
        verifyNoInteractions(cartDomainService, orderDomainService, orderTransactionExecutor,
                orderItemService, orderItemMapper);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    private Order existingOrder(CreateOrderDTO request) {
        Order order = new Order();
        order.setId(501L);
        order.setUserId(USER_ID);
        order.setRequestId(request.getRequestId());
        order.setRequestHash(fingerprint.calculate(request));
        return order;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertLookupUsesOriginalUniqueKey(String requestId) {
        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(orderMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("user_id"));
        assertTrue(wrapper.getSqlSegment().contains("request_id"));
        assertFalse(wrapper.getSqlSegment().contains("request_hash"));
        assertEquals(2, wrapper.getParamNameValuePairs().size());
        assertEquals(Set.of(USER_ID, requestId), new HashSet<>(wrapper.getParamNameValuePairs().values()));
    }
}
