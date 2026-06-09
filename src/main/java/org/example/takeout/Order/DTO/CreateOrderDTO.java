package org.example.takeout.Order.DTO;

import lombok.Data;


@Data
public class CreateOrderDTO {
    //注释的是废弃方案-直接购买模式
//    private Long id;
//    private Long orderId;
//    private Long productId;
//
//    private Integer quantity;

    //现在执行方案-购物车下单模式，从购物车查询
    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String remark;
}
