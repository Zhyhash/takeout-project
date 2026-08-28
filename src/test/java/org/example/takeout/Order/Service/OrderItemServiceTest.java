package org.example.takeout.Order.Service;

import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Product.Service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class OrderItemServiceTest {

    @Mock
    private ProductService productService;
    @Mock
    private OrderItemMapper orderItemMapper;
    @InjectMocks
    private OrderItemService orderItemService;

    @Test
    void decreaseStocksOrdersLocksByProductIdWithoutChangingCartItemOrder() {
        List<CartItem> cartItems = new ArrayList<>(List.of(
                cartItem(30L, 3),
                cartItem(10L, 1),
                cartItem(20L, 2)
        ));

        orderItemService.decreaseStocksOrderedByProductId(cartItems);

        InOrder calls = inOrder(productService);
        calls.verify(productService).decreaseStock(10L, 1);
        calls.verify(productService).decreaseStock(20L, 2);
        calls.verify(productService).decreaseStock(30L, 3);
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

        InOrder calls = inOrder(productService);
        calls.verify(productService).increaseStock(10L, 1);
        calls.verify(productService).increaseStock(20L, 2);
        calls.verify(productService).increaseStock(30L, 3);
        assertEquals(List.of(30L, 10L, 20L),
                orderItems.stream().map(OrderItem::getProductId).toList());
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
