package org.example.takeout.Product.Cache;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductDetailCacheDTO {

    private Long id;

    private Long merchantId;

    private Long categoryId;

    private String productName;

    private String description;

    private String imageUrl;

    private BigDecimal price;

    private Boolean inStock;

    private Integer status;

}
