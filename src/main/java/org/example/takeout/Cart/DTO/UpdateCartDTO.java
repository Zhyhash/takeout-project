package org.example.takeout.Cart.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCartDTO {
        @NotNull(message = "购物车记录ID不能为空")
        private Long cartItemId; // 明确指出要修改购物车里的哪一行记录
        @Max(value = 1)
        @Min(value = -1)
        private Integer quantityChange; //修改的变量，只能为1/-1
}
