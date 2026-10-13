package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Service.CartCheckoutService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.Assembler.OrderVOAssembler;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceIdempotencyTest {
    private static final Long USER_ID = 21L;

    @Mock private OrderTransactionExecutor orderTransactionExecutor;
    @Mock private OrderItemService orderItemService;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderVOAssembler orderVOAssembler;
    @Mock private OrderConvertor orderConvertor;
    @Mock private CartCheckoutService CartCheckoutService;
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
        verifyNoInteractions(CartCheckoutService, orderTransactionExecutor,
                orderItemService, orderItemMapper, orderVOAssembler, orderConvertor);
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
        when(orderConvertor.toCreateOrderVO(existing)).thenReturn(expected);
        CreateOrderDTO retry = TestDataFactory.createOrderDTO();
        retry.setRequestId(original.getRequestId());

        assertSame(expected, orderService.createOrder(retry));

        assertLookupUsesOriginalUniqueKey(original.getRequestId());
        verifyNoInteractions(CartCheckoutService, orderTransactionExecutor,
                orderItemService, orderItemMapper);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void emptyCartAfterConcurrentCommitReturnsMatchingOrderFromSecondLookup() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO request = TestDataFactory.createOrderDTO();
        Order committed = existingOrder(request);
        CreateOrderVO expected = new CreateOrderVO();
        expected.setOrderId(committed.getId());

        // B misses A's order first; A commits and consumes the cart before B prepares it.
        when(orderMapper.selectOne(any())).thenReturn(null, committed);
        when(CartCheckoutService.prepareCheckout(USER_ID)).thenReturn(new CartAvailableResult());
        when(orderConvertor.toCreateOrderVO(committed)).thenReturn(expected);

        assertSame(expected, orderService.createOrder(request));

        InOrder sequence = inOrder(orderMapper, CartCheckoutService, orderConvertor);
        sequence.verify(orderMapper).selectOne(any());
        sequence.verify(CartCheckoutService).prepareCheckout(USER_ID);
        sequence.verify(orderMapper).selectOne(any());
        sequence.verify(orderConvertor).toCreateOrderVO(committed);
        assertLookupUsesOriginalUniqueKey(request.getRequestId(), 2);
        verifyNoInteractions(orderTransactionExecutor, orderItemService, orderItemMapper);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void unavailableCartAfterFirstMissAlsoReplaysMatchingCommittedOrder() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO request = TestDataFactory.createOrderDTO();
        Order committed = existingOrder(request);
        CreateOrderVO expected = new CreateOrderVO();
        CartAvailableResult checkout = new CartAvailableResult();
        checkout.setAllItems(List.of(new CartItem()));
        checkout.setAvailableItems(List.of());
        when(orderMapper.selectOne(any())).thenReturn(null, committed);
        when(CartCheckoutService.prepareCheckout(USER_ID)).thenReturn(checkout);
        when(orderConvertor.toCreateOrderVO(committed)).thenReturn(expected);

        assertSame(expected, orderService.createOrder(request));

        assertLookupUsesOriginalUniqueKey(request.getRequestId(), 2);
        verifyNoInteractions(orderTransactionExecutor, orderItemService, orderItemMapper);
    }

    @Test
    void secondLookupRejectsChangedParametersAfterConcurrentCommit() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO original = TestDataFactory.createOrderDTO();
        Order committed = existingOrder(original);
        CreateOrderDTO changed = TestDataFactory.createOrderDTO();
        changed.setRequestId(original.getRequestId());
        changed.setRemark("不要辣椒");
        when(orderMapper.selectOne(any())).thenReturn(null, committed);
        when(CartCheckoutService.prepareCheckout(USER_ID)).thenReturn(new CartAvailableResult());

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderService.createOrder(changed));

        assertEquals(ResultCodeEnum.PARAM_ERROR, failure.getCodeEnum());
        assertEquals("同一 requestId 不能携带不同下单参数，请使用新的 requestId", failure.getMessage());
        assertLookupUsesOriginalUniqueKey(original.getRequestId(), 2);
        verifyNoInteractions(orderTransactionExecutor, orderItemService, orderItemMapper, orderConvertor);
    }

    @Test
    void missingOrderOnSecondLookupPreservesOriginalCartFailure() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO request = TestDataFactory.createOrderDTO();
        BusinessException original = new BusinessException(ResultCodeEnum.CART_EMPTY, "购物车为空，无法创建订单");
        when(CartCheckoutService.prepareCheckout(USER_ID)).thenThrow(original);

        assertSame(original, assertThrows(BusinessException.class,
                () -> orderService.createOrder(request)));

        assertLookupUsesOriginalUniqueKey(request.getRequestId(), 2);
        verifyNoInteractions(orderTransactionExecutor, orderItemService, orderItemMapper, orderConvertor);
    }

    @Test
    void otherBusinessFailureIsNotConvertedToReplayOrRetriedLookup() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO request = TestDataFactory.createOrderDTO();
        BusinessException original = new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品价格异常");
        when(CartCheckoutService.prepareCheckout(USER_ID)).thenThrow(original);

        assertSame(original, assertThrows(BusinessException.class,
                () -> orderService.createOrder(request)));

        assertLookupUsesOriginalUniqueKey(request.getRequestId());
        verifyNoInteractions(orderTransactionExecutor, orderItemService, orderItemMapper, orderConvertor);
    }

    @Test
    void cartEmptyFallbackDoesNotReplayCommittedOrderWhenNewCartAmountIsExcessive() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO request = TestDataFactory.createOrderDTO();
        Order committed = existingOrder(request);
        AtomicBoolean orderCommitted = new AtomicBoolean(false);

        // A's matching order becomes queryable after B's initial miss.
        when(orderMapper.selectOne(any())).thenAnswer(invocation -> orderCommitted.get() ? committed : null);
        when(CartCheckoutService.prepareCheckout(USER_ID)).thenAnswer(invocation -> {
            assertFalse(orderCommitted.get());
            orderCommitted.set(true);

            // After A consumes the original cart, the user adds a different, valid-priced product.
            // The actual OrderService calculation, rather than a mocked exception, exceeds its new limit.
            Product product = TestDataFactory.createProduct(101L, "新商品", 1, 301L);
            product.setPrice(new BigDecimal("1000000.01"));
            CartItem item = TestDataFactory.createCartItem(701L, USER_ID, product, 1);
            CartAvailableResult newCart = new CartAvailableResult();
            newCart.setAllItems(List.of(item));
            newCart.setAvailableItems(List.of(item));
            newCart.setProductMap(Map.of(product.getId(), product));
            newCart.setMerchantId(301L);
            newCart.setMerchantName("Test merchant");
            return newCart;
        });

        // Record the remaining limitation: the CART_EMPTY fallback does not cover this business failure.
        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderService.createOrder(request));

        assertTrue(orderCommitted.get());
        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("商品总金额超过最大结算数，请清理购物车", failure.getMessage());
        InOrder sequence = inOrder(orderMapper, CartCheckoutService);
        sequence.verify(orderMapper).selectOne(any());
        sequence.verify(CartCheckoutService).prepareCheckout(USER_ID);
        assertLookupUsesOriginalUniqueKey(request.getRequestId());
        verify(orderMapper, never()).insert(any(Order.class));
        verifyNoInteractions(orderTransactionExecutor, orderItemService, orderItemMapper, orderConvertor);
    }

    @Test
    void unexpectedPreparationFailureIsNotConvertedToReplay() {
        UserContextHolder.setUserId(USER_ID);
        CreateOrderDTO request = TestDataFactory.createOrderDTO();
        IllegalStateException original = new IllegalStateException("checkout unavailable");
        when(CartCheckoutService.prepareCheckout(USER_ID)).thenThrow(original);

        assertSame(original, assertThrows(IllegalStateException.class,
                () -> orderService.createOrder(request)));

        assertLookupUsesOriginalUniqueKey(request.getRequestId());
        verifyNoInteractions(orderTransactionExecutor, orderItemService, orderItemMapper, orderConvertor);
    }

    private Order existingOrder(CreateOrderDTO request) {
        Order order = new Order();
        order.setId(501L);
        order.setUserId(USER_ID);
        order.setRequestId(request.getRequestId());
        order.setRequestHash(fingerprint.calculate(request));
        return order;
    }

    private void assertLookupUsesOriginalUniqueKey(String requestId) {
        assertLookupUsesOriginalUniqueKey(requestId, 1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertLookupUsesOriginalUniqueKey(String requestId, int expectedLookups) {
        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(orderMapper, times(expectedLookups)).selectOne(captor.capture());
        for (Wrapper<Order> captured : captor.getAllValues()) {
            AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captured;
            assertTrue(wrapper.getSqlSegment().contains("user_id"));
            assertTrue(wrapper.getSqlSegment().contains("request_id"));
            assertFalse(wrapper.getSqlSegment().contains("request_hash"));
            assertEquals(2, wrapper.getParamNameValuePairs().size());
            assertEquals(Set.of(USER_ID, requestId), new HashSet<>(wrapper.getParamNameValuePairs().values()));
        }
    }
}
