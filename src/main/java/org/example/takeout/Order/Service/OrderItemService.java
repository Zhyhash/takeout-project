package org.example.takeout.Order.Service;

import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class OrderItemService {
    @Autowired
    private ProductService productService;
    @Autowired
    private OrderItemMapper orderItemMapper;

    //NOTE:按商品 ID 升序扣减库存，确保并发订单以相同顺序获取商品行锁。
    @Transactional(rollbackFor = Exception.class)
    public void decreaseStocksOrderedByProductId(List<CartItem> cartItems) {
        List<CartItem> orderedItems = new ArrayList<>(cartItems);
        orderedItems.sort(Comparator.comparing(CartItem::getProductId));

        for (CartItem item : orderedItems) {
            productService.decreaseStock(item.getProductId(), item.getQuantity());
        }
    }


    //NOTE:库存归还也使用相同的商品 ID 锁顺序，避免与扣减库存形成反向等待。
    @Transactional(rollbackFor = Exception.class)
    public void increaseStocksOrderedByProductId(List<OrderItem> orderItems) {
        List<OrderItem> orderedItems = new ArrayList<>(orderItems);
        orderedItems.sort(Comparator.comparing(OrderItem::getProductId));

        for (OrderItem item : orderedItems) {
            productService.increaseStock(item.getProductId(), item.getQuantity());
        }
    }

    public List<OrderItem> buildOrderItems(Order order, List<CartItem> availableCartItems, Map<Long, Product> productMap) {

        List<OrderItem> orderItems = new ArrayList<>();

        for (CartItem cartItem : availableCartItems) {
            Product product = productMap.get(cartItem.getProductId());
            if (product == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在");
            }

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(product.getId());
            item.setProductName(product.getProductName());
            item.setProductPrice(product.getPrice());
            item.setQuantity(cartItem.getQuantity());
            item.setSubtotal(product.getPrice().multiply(new BigDecimal(cartItem.getQuantity())));
            item.setProductPicture(resolveProductPicture(product, cartItem));
            orderItems.add(item);
        }

        return orderItems;

    }

    private String resolveProductPicture(Product product, CartItem cartItem) {
        String imageUrl = product.getImageUrl();
        if (imageUrl != null && !imageUrl.isBlank()) {
            return imageUrl;
        }
        String productImage = cartItem.getProductImage();
        if (productImage != null && !productImage.isBlank()) {
            return productImage;
        }
        return ProductService.DEFAULT_PRODUCT_IMAGE_URL;
    }


    public void saveBatch(List<OrderItem> items){
        for (OrderItem item : items) {
            if (orderItemMapper.insert(item) != 1) {
                throw new BusinessException(ResultCodeEnum.DATABASE_ERROR, "订单明细保存失败");
            }
        }
    }
}
