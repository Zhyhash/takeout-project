package org.example.tokeout.Order.VO;


import lombok.Data;

import java.math.BigDecimal;

//单个订单返回VO
@Data
public class OrderItemVO {

    private Long productId;

    private String productName;

    private String productPicture;

    private BigDecimal productPrice;

    private Integer quantity;

    private BigDecimal subtotal;
}
