package org.example.tokeout.Product.DTO;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
@Data
public class CreateProductDTO {
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 15, message = "商品名称长度不能超过15个字符")
    private String productName;

    @Size(max = 100, message = "商品描述长度不能超过100个字符")
    private String description;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.00", inclusive = false, message = "商品价格必须大于0")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存数量不能为负数")
    private Integer stock;

    private String imageUrl;

    @NotNull(message = "分类ID不能为空")
    private Long categoryId;
}
