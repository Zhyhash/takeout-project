package org.example.tokeout.Cart.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCartDTO {
        @NotNull(message = "购物车记录ID不能为空")
        private Long cartItemId; // 明确指出要修改购物车里的哪一行记录
        //TODO：这里其实是delta表示加减变量，但是为了REST规范，此处需要修改喵，也可以不修改
        //TODO：如果需要修改，额外去controller里面修一下接口，变成PutMapping(现在是@PatchMapping)
        private Integer delta; //最后的变量
}
