package org.example.tokeout.Cart.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SubCartDTO {
    @NotNull(message = "商品id不能为空")
    private Long productId;
}
