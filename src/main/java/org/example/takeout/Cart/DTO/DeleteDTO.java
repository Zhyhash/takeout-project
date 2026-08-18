package org.example.takeout.Cart.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

@Data
public class DeleteDTO {
    @NotEmpty(message = "购物车记录ID不能为空")
    @UniqueElements(message = "购物车记录ID不能重复")
    private List<@Positive(message = "购物车记录ID必须为正数") Long> cartItemIds;

    public DeleteDTO(List<Long> ids) {
        this.cartItemIds = ids;
    }
}
