package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.VO.OrderItemVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Service.ProductCommandService;
import org.example.takeout.Product.Service.ProductService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderItemServiceTest {

    @Mock
    private ProductCommandService productCommandService;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderConvertor orderConvertor;
    @InjectMocks
    private OrderItemService orderItemService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderItem.class);
    }

    @Test
    void decreaseStocksOrdersLocksByProductIdWithoutChangingCartItemOrder() {
        List<CartItem> cartItems = new ArrayList<>(List.of(
                cartItem(30L, 3),
                cartItem(10L, 1),
                cartItem(20L, 2)
        ));

        orderItemService.decreaseStocksOrderedByProductId(cartItems);

        InOrder calls = inOrder(productCommandService);
        calls.verify(productCommandService).decreaseStock(10L, 1);
        calls.verify(productCommandService).decreaseStock(20L, 2);
        calls.verify(productCommandService).decreaseStock(30L, 3);
        assertEquals(List.of(30L, 10L, 20L),
                cartItems.stream().map(CartItem::getProductId).toList());
    }

    @Test
    void increaseStocksOrdersLocksByProductIdWithoutChangingOrderItemOrder() {
        List<OrderItem> orderItems = new ArrayList<>(List.of(
                orderItem(30L, 3),
                orderItem(10L, 1),
                orderItem(20L, 2)
        ));

        orderItemService.increaseStocksOrderedByProductId(orderItems);

        InOrder calls = inOrder(productCommandService);
        calls.verify(productCommandService).increaseStock(10L, 1);
        calls.verify(productCommandService).increaseStock(20L, 2);
        calls.verify(productCommandService).increaseStock(30L, 3);
        assertEquals(List.of(30L, 10L, 20L),
                orderItems.stream().map(OrderItem::getProductId).toList());
    }

    @Test
    void orderItemSnapshotUsesCurrentProductDataAndImage() {
        CartItem cartItem = cartItem(10L, 2);
        cartItem.setProductImage("/images/cart-old.png");
        OrderItem snapshot = buildSnapshot(cartItem, product("/images/product-current.png"));

        assertEquals(501L, snapshot.getOrderId());
        assertEquals(10L, snapshot.getProductId());
        assertEquals("Current product", snapshot.getProductName());
        assertEquals(new BigDecimal("12.50"), snapshot.getProductPrice());
        assertEquals(2, snapshot.getQuantity());
        assertEquals(new BigDecimal("25.00"), snapshot.getSubtotal());
        assertEquals("/images/product-current.png", snapshot.getProductPicture());
    }

    @Test
    void missingProductImageFallsBackToCartImage() {
        CartItem cartItem = cartItem(10L, 2);
        cartItem.setProductImage("/images/cart.png");

        assertEquals("/images/cart.png", buildSnapshot(cartItem, product("  ")).getProductPicture());
    }

    @Test
    void missingProductAndCartImagesFallBackToDefault() {
        CartItem cartItem = cartItem(10L, 2);
        cartItem.setProductImage(" ");

        assertEquals(ProductService.DEFAULT_PRODUCT_IMAGE_URL,
                buildSnapshot(cartItem, product(null)).getProductPicture());
    }

    @Test
    void missingProductRejectsOrderItemSnapshot() {
        Order order = new Order();
        order.setId(501L);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderItemService.buildOrderItems(order, List.of(cartItem(10L, 1)), Map.of()));

        assertEquals("商品不存在", failure.getMessage());
        verifyNoInteractions(orderItemMapper, productCommandService);
    }

    @Test
    void savingDetailsStopsAtFailedInsert() {
        OrderItem first = orderItem(10L, 1);
        OrderItem failed = orderItem(20L, 2);
        OrderItem last = orderItem(30L, 3);
        when(orderItemMapper.insert(first)).thenReturn(1);
        when(orderItemMapper.insert(failed)).thenReturn(0);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> orderItemService.saveBatch(List.of(first, failed, last)));

        assertEquals(ResultCodeEnum.DATABASE_ERROR, failure.getCodeEnum());
        verify(orderItemMapper, never()).insert(last);
    }

    @Test
    void merchantItemQueriesSkipEmptyOrderIds() {
        assertTrue(orderItemService.findMerchantOrderItemsByOrderIds(null).isEmpty());
        assertTrue(orderItemService.findMerchantOrderItemVOsByOrderIds(List.of()).isEmpty());
        verifyNoInteractions(orderItemMapper, orderConvertor);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void merchantItemQueryUsesProvidedOrderIdsAndReturnsConvertedViews() {
        OrderItem item = orderItem(10L, 2);
        item.setOrderId(501L);
        OrderItemVO view = new OrderItemVO();
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));
        when(orderConvertor.toOrderItemVO(item)).thenReturn(view);

        assertEquals(List.of(view), orderItemService.findMerchantOrderItemVOsByOrderIds(List.of(501L, 502L)));

        ArgumentCaptor<Wrapper<OrderItem>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(orderItemMapper).selectList(query.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) query.getValue();
        assertTrue(wrapper.getSqlSegment().contains("order_id IN"));
        assertEquals(Set.of(501L, 502L), Set.copyOf(wrapper.getParamNameValuePairs().values()));
    }

    @Test
    void missingMerchantOrderItemsReturnEmptyLists() {
        when(orderItemMapper.selectList(any())).thenReturn(null);

        assertTrue(orderItemService.findMerchantOrderItemsByOrderIds(List.of(501L)).isEmpty());
        assertTrue(orderItemService.findMerchantOrderItemVOsByOrderIds(List.of(501L)).isEmpty());
        verifyNoInteractions(orderConvertor);
    }

    private OrderItem buildSnapshot(CartItem cartItem, Product product) {
        Order order = new Order();
        order.setId(501L);
        return orderItemService.buildOrderItems(order, List.of(cartItem), Map.of(10L, product)).get(0);
    }

    private Product product(String image) {
        Product product = new Product();
        product.setId(10L);
        product.setProductName("Current product");
        product.setPrice(new BigDecimal("12.50"));
        product.setImageUrl(image);
        return product;
    }

    private CartItem cartItem(Long productId, Integer quantity) {
        CartItem item = new CartItem();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private OrderItem orderItem(Long productId, Integer quantity) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }
}
