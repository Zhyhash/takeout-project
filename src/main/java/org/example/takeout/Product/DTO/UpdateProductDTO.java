package org.example.takeout.Product.DTO;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NonNull;

import java.math.BigDecimal;


@Data
public class UpdateProductDTO {
    @Size(max = 15, message = "商品名称长度不能超过15个字符")
    @NotBlank(message = "商品名称不得为空")
    private String productName;

    @Size(max = 100, message = "商品描述长度不能超过100个字符")
    private String description;

    @DecimalMin(value = "0.00", message = "商品价格不能为负数")
    @DecimalMax(value = "99999.99", message = "商品价格不能超过99999.99")
    private BigDecimal price;

    @Min(value = 0, message = "库存数量不能为负数")
    @Max(value = 10000, message = "库存不能超过10000")
    private Integer stock;

    @Size(max = 255, message = "商品图片URL长度不能超过255个字符")
    private String imageUrl;

    @Positive(message = "分类ID必须大于0")
    private Long categoryId;

    @NotNull(message = "商品版本号不能为空")
    private Integer version;
}
