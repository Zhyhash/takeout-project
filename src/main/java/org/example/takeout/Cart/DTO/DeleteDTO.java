package org.example.takeout.Cart.DTO;

import lombok.Data;

import java.util.List;

@Data
public class DeleteDTO {
    private List<Long> cartItemIds;

    public DeleteDTO(List<Long> ids) {
        this.cartItemIds = ids;
    }
}
