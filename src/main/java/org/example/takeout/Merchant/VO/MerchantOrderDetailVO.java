package org.example.takeout.Merchant.VO;

import lombok.Data;
import org.example.takeout.Order.VO.OrderItemVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data


public class MerchantOrderDetailVO {

    private Long orderId;

    private BigDecimal totalAmount;

    private Integer status;

    private LocalDateTime createTime;

    private List<OrderItemVO> items;

}


