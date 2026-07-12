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
    private Integer status;//不需要存量了，只让用户看见还有没有的那种状态就可以了
    private BigDecimal price;
}
