package org.example.takeout.Cart.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddCartDTO {
    @NotNull(message = "商品id不能为空")
    private Long productId;
}
