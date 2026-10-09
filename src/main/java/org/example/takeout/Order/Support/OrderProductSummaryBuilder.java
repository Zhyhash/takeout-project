package org.example.takeout.Order.Support;

import org.example.takeout.Order.Entity.OrderItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderProductSummaryBuilder {

    public String buildProductSummary(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }

        int totalQuantity = items.stream().
                mapToInt(OrderItem::getQuantity).
                sum();

        String firstProductName = items.get(0).getProductName();

        if (totalQuantity == 1) {
            return firstProductName;
        }

        return String.format("%s 等 %d 件商品",
                firstProductName, totalQuantity);
    }
}
