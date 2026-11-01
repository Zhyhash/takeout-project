package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Cart.Service.CartCommandService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderTransactionExecutorIdempotencyTest {
    private static final Long USER_ID = 21L;
    @Mock private OrderItemService orderItemService;
    @Mock private OrderConvertor orderConvertor;
    @Mock private OrderMapper orderMapper;
    @Mock private CartCommandService cartCommandService;
    @InjectMocks private OrderTransactionExecutor executor;

    private CreateOrderDTO request;
    private OrderDataContext context;
    private String requestHash;
    private DuplicateKeyException duplicateKey;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @BeforeEach
    void prepareDuplicateInsert() {
        request = TestDataFactory.createOrderDTO();
        request.setLongitude(new BigDecimal("116.397"));
        request.setLatitude(new BigDecimal("39.908"));
        requestHash = new OrderRequestFingerprint().calculate(request);
        context = new OrderDataContext();
        context.setMerchantId(31L);
        context.setMerchantName("Test merchant");
        context.setTotalAmount(new BigDecimal("10.00"));
        context.setDeliveryFee(new BigDecimal("5.00"));
        duplicateKey = new DuplicateKeyException("Duplicate entry for unique key");
        when(orderMapper.insert(any(Order.class))).thenThrow(duplicateKey);
    }

    @Test
    void concurrentSameParametersReturnCommittedOrderWithoutFurtherSideEffects() {
        Order committed = committedOrder(requestHash);
        when(orderMapper.selectOne(any())).thenReturn(committed);

        CreateOrderDTO equivalentRetry = TestDataFactory.createOrderDTO();
        equivalentRetry.setRequestId(request.getRequestId());
        equivalentRetry.setLongitude(new BigDecimal("116.3970000"));
        equivalentRetry.setLatitude(new BigDecimal("39.908000"));
        String equivalentRetryHash = new OrderRequestFingerprint().calculate(equivalentRetry);
        assertEquals(requestHash, equivalentRetryHash);

        assertSame(committed, executor.executeOrderCreation(
                context, equivalentRetry, USER_ID, equivalentRetryHash));

        assertLookupUsesOriginalUniqueKey();
        verifyNoInteractions(cartCommandService, orderItemService);
    }

    @Test
    void concurrentChangedParametersProduceBusinessErrorWithoutFurtherSideEffects() {
        CreateOrderDTO firstRequest = TestDataFactory.createOrderDTO();
        firstRequest.setRequestId(request.getRequestId());
        firstRequest.setLongitude(request.getLongitude());
        firstRequest.setLatitude(request.getLatitude());
        firstRequest.setRemark("不要辣椒");
        Order committed = committedOrder(new OrderRequestFingerprint().calculate(firstRequest));
        when(orderMapper.selectOne(any())).thenReturn(committed);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executor.executeOrderCreation(context, request, USER_ID, requestHash));

        assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        assertEquals("同一 requestId 不能携带不同下单参数，请使用新的 requestId", exception.getMessage());
        assertLookupUsesOriginalUniqueKey();
        verifyNoInteractions(cartCommandService, orderItemService);
    }

    @Test
    void concurrentChangedReceiverCoordinatesProduceBusinessErrorWithoutFurtherSideEffects() {
        CreateOrderDTO firstRequest = TestDataFactory.createOrderDTO();
        firstRequest.setRequestId(request.getRequestId());
        firstRequest.setLongitude(request.getLongitude());
        firstRequest.setLatitude(request.getLatitude());
        Order committed = committedOrder(new OrderRequestFingerprint().calculate(firstRequest));
        when(orderMapper.selectOne(any())).thenReturn(committed);

        CreateOrderDTO changedRequest = TestDataFactory.createOrderDTO();
        changedRequest.setRequestId(request.getRequestId());
        changedRequest.setLongitude(request.getLongitude());
        changedRequest.setLatitude(new BigDecimal("39.909"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executor.executeOrderCreation(context, changedRequest, USER_ID,
                        new OrderRequestFingerprint().calculate(changedRequest)));

        assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        assertEquals("同一 requestId 不能携带不同下单参数，请使用新的 requestId", exception.getMessage());
        assertLookupUsesOriginalUniqueKey();
        verifyNoInteractions(cartCommandService, orderItemService);
    }

    @Test
    void otherUniqueKeyViolationKeepsOriginalException() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertSame(duplicateKey, assertThrows(DuplicateKeyException.class,
                () -> executor.executeOrderCreation(context, request, USER_ID, requestHash)));

        assertLookupUsesOriginalUniqueKey();
        verifyNoInteractions(cartCommandService, orderItemService);
    }

    @Test
    void newOrderIsInitiallyEligibleForTimeoutCancellationAfterThirtyMinutes() {
        when(orderMapper.selectOne(any())).thenReturn(committedOrder(requestHash));

        executor.executeOrderCreation(context, request, USER_ID, requestHash);

        ArgumentCaptor<Order> insertedOrder = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(insertedOrder.capture());
        LocalDateTime createdAt = insertedOrder.getValue().getCreateTime();
        assertNotNull(createdAt);
        assertEquals(createdAt.plusMinutes(30), insertedOrder.getValue().getTimeoutCancelAvailableAt());
        assertEquals(new BigDecimal("10.00"), insertedOrder.getValue().getOriginalAmount());
        assertEquals(new BigDecimal("5.00"), insertedOrder.getValue().getDeliveryFee());
        assertEquals(new BigDecimal("15.00"), insertedOrder.getValue().getTotalAmount());
    }

    private Order committedOrder(String hash) {
        Order order = new Order();
        order.setId(501L);
        order.setUserId(USER_ID);
        order.setRequestId(request.getRequestId());
        order.setRequestHash(hash);
        return order;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertLookupUsesOriginalUniqueKey() {
        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(orderMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("user_id"));
        assertTrue(wrapper.getSqlSegment().contains("request_id"));
        assertFalse(wrapper.getSqlSegment().contains("request_hash"));
        assertEquals(2, wrapper.getParamNameValuePairs().size());
        assertEquals(Set.of(USER_ID, request.getRequestId()), new HashSet<>(wrapper.getParamNameValuePairs().values()));
    }
}
