package org.example.tokeout.Product.DTO;

import lombok.Data;

import java.math.BigDecimal;
@Data
public class UpdateProductDTO {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private String imageUrl;
    private Long categoryId;
    private String categoryName;
}
