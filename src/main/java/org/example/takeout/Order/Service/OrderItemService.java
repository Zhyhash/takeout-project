package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//TODO：嗯，目前来说，这个方法发挥了0个作用，一开始是为了测试orderItem的事务
@Service
public class OrderItemService {
    @Autowired
    private ProductService productService;
    public List<OrderItem> buildOrderItems(Order order, List<CartItem> availableCartItems, Map<Long, Product> productMap) {

        List<OrderItem> orderItems = new ArrayList<>();

        for (CartItem cartItem : availableCartItems) {
            Product product = productMap.get(cartItem.getProductId());
            if (product == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在");
            }

            productService.decreaseStock(product,cartItem.getQuantity());

            // 组装订单详情从表
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(product.getId());
            item.setProductName(product.getProductName());
            item.setProductPrice(product.getPrice());
            item.setQuantity(cartItem.getQuantity());
            item.setSubtotal(product.getPrice().multiply(new BigDecimal(cartItem.getQuantity())));
            orderItems.add(item);
        }

        return orderItems;

    }


    public void saveBatch(List<OrderItem> items){

        Db.saveBatch(items);

    }
}
