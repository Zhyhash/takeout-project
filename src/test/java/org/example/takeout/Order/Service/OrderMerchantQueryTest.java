package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderMerchantQueryTest {
    @Mock private OrderMapper orderMapper;
    @InjectMocks private OrderService orderService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @Test
    void pendingOrdersAreScopedToMerchantAndPaidStatus() {
        Order order = new Order();
        when(orderMapper.selectList(any())).thenReturn(List.of(order));

        assertEquals(List.of(order), orderService.findMerchantPendingOrders(101L));

        assertMerchantListQuery(Set.of(101L, OrderStatusEnum.PAID.getCode()));
    }

    @Test
    void processingOrdersAreScopedToMerchantAndPreparingStatus() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertTrue(orderService.findMerchantProcessingOrders(101L).isEmpty());

        assertMerchantListQuery(Set.of(101L, OrderStatusEnum.PREPARING.getCode()));
    }

    @Test
    void historyOrdersIncludeReadyThroughFinishedAndExcludeUnpaidOrCancelled() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertTrue(orderService.findMerchantHistoryOrders(101L).isEmpty());

        assertMerchantListQuery(Set.of(101L,
                OrderStatusEnum.READY.getCode(), OrderStatusEnum.DELIVERING.getCode(),
                OrderStatusEnum.DELIVERED.getCode(), OrderStatusEnum.FINISHED.getCode()));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void merchantOrderDetailLookupIncludesBothOrderAndMerchantId() {
        Order order = new Order();
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertSame(order, orderService.findMerchantOrderById(501L, 101L));

        ArgumentCaptor<Wrapper<Order>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(orderMapper).selectOne(query.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) query.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("id ="));
        assertTrue(sql.contains("merchant_id ="));
        assertEquals(Set.of(501L, 101L), Set.copyOf(wrapper.getParamNameValuePairs().values()));
        verifyNoMoreInteractions(orderMapper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertMerchantListQuery(Set<Object> expectedValues) {
        ArgumentCaptor<Wrapper<Order>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(orderMapper).selectList(query.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) query.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("merchant_id ="));
        assertTrue(sql.contains("status IN"));
        assertTrue(sql.contains("ORDER BY create_time DESC"));
        assertEquals(expectedValues, Set.copyOf(wrapper.getParamNameValuePairs().values()));
        verifyNoMoreInteractions(orderMapper);
    }
}
