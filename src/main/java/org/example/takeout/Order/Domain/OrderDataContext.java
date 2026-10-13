package org.example.takeout.Order.Domain;

import lombok.Data;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Product.Entity.Product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class OrderDataContext {
    private List<CartItem> availableItems;
    private Map<Long, Product> productMap;
    private Long merchantId;
    private String merchantName;
    private BigDecimal totalAmount;
}
