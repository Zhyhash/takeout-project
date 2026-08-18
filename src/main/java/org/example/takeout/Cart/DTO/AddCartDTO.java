package org.example.takeout.Cart.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AddCartDTO {
    @NotNull(message = "商品id不能为空")
    @Positive(message = "商品id必须为正数")
    private Long productId;
}
