package org.example.takeout.Cart.VO;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartVO {
    private Long id;

    private Long productId;
    private String productName;
    private String productImage;

    private BigDecimal price;

    private Integer quantity;

    private BigDecimal subtotal;//临时计算字段

    private Boolean available = true;        // 默认 true，兼容旧逻辑
    private String disableReason;            // 不可用原因："商家打烊"、"商品已下架"等



}
