package org.example.takeout.Merchant.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家订单列表项。
 */
@Data
public class MerchantOrderListVO {

    private Long orderId;

    private String orderNo;

    private String receiverName;

    private BigDecimal totalAmount;

    private String productSummary;

    private Integer status;

    private String statusDesc;

    private LocalDateTime createTime;
}
