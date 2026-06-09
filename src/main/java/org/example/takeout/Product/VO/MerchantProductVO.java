package org.example.takeout.Product.VO;

import lombok.Data;

import java.math.BigDecimal;

//NOTE:商家查看商品后台
@Data
public class MerchantProductVO {
    private Long id;
    private String categoryName;
    private String productName;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private String description;
    private String imageUrl;
}
