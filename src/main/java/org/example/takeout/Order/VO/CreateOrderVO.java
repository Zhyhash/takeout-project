package org.example.takeout.Order.VO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateOrderVO {
    private Long orderId;
    private String orderNo;
}

