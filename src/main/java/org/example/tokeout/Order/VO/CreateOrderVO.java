package org.example.tokeout.Order.VO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateOrderVO {
    @NotNull(message = "订单id不为空")
    private Long orderId;
    @NotNull(message = "订单编号不为空")
    private String orderNo;
}

