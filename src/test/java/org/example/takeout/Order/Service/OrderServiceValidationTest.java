package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Service.CartCheckoutService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.Assembler.OrderVOAssembler;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.DeliveryFeeCalculator;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderCancelSourceEnum;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Product.Entity.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceValidationTest {

    @Mock
    private OrderTransactionExecutor orderTransactionExecutor;
    @Mock
    private OrderItemService orderItemService;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderVOAssembler orderVOAssembler;
    @Mock
    private OrderConvertor orderConvertor;
    @Mock
    private CartCheckoutService CartCheckoutService;
    @Spy
    private OrderRequestFingerprint orderRequestFingerprint = new OrderRequestFingerprint();
    @Spy
    private DeliveryFeeCalculator deliveryFeeCalculator = new DeliveryFeeCalculator();
    @InjectMocks
    private OrderService orderService;

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void createOrderRejectsCartWhenAnyItemIsUnavailable() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-with-unavailable-item");

        CartItem availableItem = cartItem(1L);
        CartItem unavailableItem = cartItem(2L);
        CartAvailableResult result = new CartAvailableResult();
        result.setAllItems(List.of(availableItem, unavailableItem));
        result.setAvailableItems(List.of(availableItem));

        when(orderMapper.selectOne(any())).thenReturn(null);
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(result);

        BusinessException failure = assertThrows(BusinessException.class, () -> orderService.createOrder(dto));

        assertEquals(ResultCodeEnum.CART_EMPTY, failure.getCodeEnum());
        assertEquals("购物车包含不可用商品或不可下单商家，请刷新购物车后重试", failure.getMessage());
        verify(orderMapper, times(2)).selectOne(any());
        verify(orderTransactionExecutor, never()).executeOrderCreation(any(), any(), any(), anyString());
    }

    @Test
    void createOrderUsesCheckoutMerchantAndCurrentProductPrice() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-with-checkout-merchant");
        setNearbyReceiver(dto);
        CartItem item = cartItem(1L);
        item.setProductId(101L);
        item.setQuantity(2);
        item.setPrice(new BigDecimal("1.00"));
        Product product = new Product();
        product.setId(101L);
        product.setPrice(new BigDecimal("12.50"));
        CartAvailableResult result = new CartAvailableResult();
        result.setAllItems(List.of(item));
        result.setAvailableItems(List.of(item));
        result.setProductMap(Map.of(101L, product));
        result.setMerchantId(301L);
        result.setMerchantName("Test merchant");
        result.setLongitude(0.0);
        result.setLatitude(0.0);
        Order order = new Order();
        order.setId(501L);
        CreateOrderVO view = new CreateOrderVO();
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(result);
        when(orderTransactionExecutor.executeOrderCreation(any(), eq(dto), eq(21L), anyString())).thenReturn(order);
        when(orderConvertor.toCreateOrderVO(order)).thenReturn(view);

        assertSame(view, orderService.createOrder(dto));

        String expectedRequestHash = new OrderRequestFingerprint().calculate(dto);
        ArgumentCaptor<OrderDataContext> context = ArgumentCaptor.forClass(OrderDataContext.class);
        verify(orderTransactionExecutor).executeOrderCreation(context.capture(), eq(dto), eq(21L),
                eq(expectedRequestHash));
        assertEquals(301L, context.getValue().getMerchantId());
        assertEquals("Test merchant", context.getValue().getMerchantName());
        assertSame(result.getProductMap(), context.getValue().getProductMap());
        assertEquals(List.of(item), context.getValue().getAvailableItems());
        assertEquals(new BigDecimal("25.00"), context.getValue().getTotalAmount());
        assertEquals(new BigDecimal("5.00"), context.getValue().getDeliveryFee());
    }

    @Test
    void createOrderAllowsExactlyMaximumSettlementAmount() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-at-maximum-amount");
        setNearbyReceiver(dto);
        CartAvailableResult checkout = checkoutWithPriceAndQuantity("499997.50", 2);
        Order order = new Order();
        CreateOrderVO expected = new CreateOrderVO();
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(checkout);
        when(orderTransactionExecutor.executeOrderCreation(any(), eq(dto), eq(21L), anyString())).thenReturn(order);
        when(orderConvertor.toCreateOrderVO(order)).thenReturn(expected);

        assertSame(expected, orderService.createOrder(dto));

        ArgumentCaptor<OrderDataContext> context = ArgumentCaptor.forClass(OrderDataContext.class);
        verify(orderTransactionExecutor).executeOrderCreation(context.capture(), eq(dto), eq(21L), anyString());
        assertEquals(new BigDecimal("999995.00"), context.getValue().getTotalAmount());
        assertEquals(new BigDecimal("5.00"), context.getValue().getDeliveryFee());
        verify(orderMapper).selectOne(any());
    }

    @ParameterizedTest
    @CsvSource({
            "1000000.01, 1",
            "99999999.99, 2",
            "1.00, 2147483647"
    })
    void createOrderRejectsExcessiveSettlementAmountBeforeAnyWrite(String price, int quantity) {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-over-maximum-amount");
        setNearbyReceiver(dto);
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(checkoutWithPriceAndQuantity(price, quantity));

        BusinessException failure = assertThrows(BusinessException.class, () -> orderService.createOrder(dto));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("商品总金额超过最大结算数，请清理购物车", failure.getMessage());
        verify(orderMapper).selectOne(any());
        verify(orderMapper, never()).insert(any(Order.class));
        verifyNoInteractions(orderTransactionExecutor, orderConvertor, orderItemService, orderItemMapper);
    }

    @Test
    void createOrderRejectsWhenDeliveryFeePushesPaymentAboveMaximum() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-fee-over-maximum-amount");
        setNearbyReceiver(dto);
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(
                checkoutWithPriceAndQuantity("999996.00", 1));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderService.createOrder(dto));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("订单支付总金额超过最大结算数，请清理购物车", failure.getMessage());
        verify(orderMapper).selectOne(any());
        verify(orderMapper, never()).insert(any(Order.class));
        verifyNoInteractions(orderTransactionExecutor, orderConvertor, orderItemService, orderItemMapper);
    }

    @Test
    void createOrderRejectsDeliveryDistanceOverThirtyKilometers() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-over-delivery-range");
        dto.setLongitude(new BigDecimal("0.28"));
        dto.setLatitude(BigDecimal.ZERO);
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(
                checkoutWithPriceAndQuantity("10.00", 1));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderService.createOrder(dto));

        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("配送距离超出服务范围（最长30公里），暂时无法下单", failure.getMessage());
        verify(orderMapper).selectOne(any());
        verify(orderMapper, never()).insert(any(Order.class));
        verifyNoInteractions(orderTransactionExecutor, orderConvertor, orderItemService, orderItemMapper);
    }

    @Test
    void createOrderRejectsCoordinatesJustOutsideDecimalBoundary() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-coordinate-outside-boundary");
        dto.setLongitude(new BigDecimal("180.00000000000000000001"));
        dto.setLatitude(BigDecimal.ZERO);
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(
                checkoutWithPriceAndQuantity("10.00", 1));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderService.createOrder(dto));

        assertEquals(ResultCodeEnum.PARAM_ERROR, failure.getCodeEnum());
        assertEquals("收货地点经纬度超出有效范围", failure.getMessage());
        verify(orderMapper).selectOne(any());
        verify(orderMapper, never()).insert(any(Order.class));
        verifyNoInteractions(orderTransactionExecutor, orderConvertor, orderItemService, orderItemMapper);
    }

    @Test
    void createOrderRejectsMissingCheckoutMerchantBeforeTransaction() {
        UserContextHolder.setUserId(21L);
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setRequestId("request-with-missing-merchant");
        setNearbyReceiver(dto);
        CartAvailableResult result = new CartAvailableResult();
        result.setAllItems(List.of(cartItem(1L)));
        result.setAvailableItems(result.getAllItems());
        when(CartCheckoutService.prepareCheckout(21L)).thenReturn(result);

        BusinessException failure = assertThrows(BusinessException.class, () -> orderService.createOrder(dto));

        assertEquals("商家不存在", failure.getMessage());
        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        verify(orderMapper).selectOne(any());
        verifyNoInteractions(orderTransactionExecutor, orderConvertor, orderItemService);
    }

    @Test
    void cancelOrderReturnsSuccessWithoutRestoringStockWhenAlreadyCancelled() {
        UserContextHolder.setUserId(22L);
        Order cancelledOrder = new Order();
        cancelledOrder.setStatus(OrderStatusEnum.CANCELLED.getCode());
        when(orderMapper.selectOne(any())).thenReturn(cancelledOrder);

        assertDoesNotThrow(() -> orderService.cancelOrder(501L));

        verifyNoInteractions(orderItemMapper, orderItemService);
    }

    @Test
    void cancelOrderRejectsMissingOrOtherUsersOrder() {
        UserContextHolder.setUserId(22L);

        BusinessException failure = assertThrows(BusinessException.class, () -> orderService.cancelOrder(501L));

        assertEquals("订单不存在或当前状态不可取消", failure.getMessage());
        verifyNoInteractions(orderItemMapper, orderItemService);
    }

    @Test
    void cancelOrderRejectsPaidOrder() {
        UserContextHolder.setUserId(22L);
        Order paidOrder = new Order();
        paidOrder.setStatus(OrderStatusEnum.PAID.getCode());
        when(orderMapper.selectOne(any())).thenReturn(paidOrder);

        BusinessException failure = assertThrows(BusinessException.class, () -> orderService.cancelOrder(501L));

        assertEquals("订单不存在或当前状态不可取消", failure.getMessage());
        verifyNoInteractions(orderItemMapper, orderItemService);
    }

    @Test
    void cancelOrderRejectsOrderWithoutDetails() {
        UserContextHolder.setUserId(22L);
        when(orderMapper.UpdateOrderStatusToCancel(
                eq(501L),
                eq(22L),
                anyList(),
                eq(OrderStatusEnum.CANCELLED.getCode()),
                eq(OrderCancelSourceEnum.USER.getCode())
        )).thenReturn(1);
        when(orderItemMapper.selectList(ArgumentMatchers.<Wrapper<OrderItem>>any())).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> orderService.cancelOrder(501L));
    }

    private CartItem cartItem(Long id) {
        CartItem item = new CartItem();
        item.setId(id);
        return item;
    }

    private CartAvailableResult checkoutWithPriceAndQuantity(String price, int quantity) {
        CartItem item = cartItem(1L);
        item.setProductId(101L);
        item.setQuantity(quantity);
        Product product = new Product();
        product.setId(101L);
        product.setPrice(new BigDecimal(price));
        CartAvailableResult checkout = new CartAvailableResult();
        checkout.setAllItems(List.of(item));
        checkout.setAvailableItems(List.of(item));
        checkout.setProductMap(Map.of(101L, product));
        checkout.setMerchantId(301L);
        checkout.setMerchantName("Test merchant");
        checkout.setLongitude(0.0);
        checkout.setLatitude(0.0);
        return checkout;
    }

    private void setNearbyReceiver(CreateOrderDTO dto) {
        dto.setLongitude(BigDecimal.ZERO);
        dto.setLatitude(BigDecimal.ZERO);
    }
}
