package org.example.takeout.Product.VO;

import lombok.Data;

import java.math.BigDecimal;

//NOTE:用户查看商品
@Data
public class ProductVO {
    private  Long id;
    private String productName;
    private String imageUrl;
    private String categoryName;
    private Boolean inStock;
    private Integer status;
    private String statusDesc;
    private BigDecimal price;
}
