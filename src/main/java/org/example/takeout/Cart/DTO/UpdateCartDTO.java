package org.example.takeout.Cart.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class UpdateCartDTO {
        @NotNull(message = "购物车记录ID不能为空")
        @Positive(message = "购物车记录ID必须为正数")
        private Long cartItemId; // 明确指出要修改购物车里的哪一行记录
        @NotNull(message = "修改的数量不能为空")
        private Integer quantityChange; //修改的变量，只能为1/-1

        @JsonIgnore
        @AssertTrue(message = "修改的数量只能为-1或1")
        public boolean isQuantityChangeValid() {
                return quantityChange == null || quantityChange == -1 || quantityChange == 1;
        }
}
