package org.example.takeout.Order.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderVO {

    private Long id;

    private String orderNo;

    private String merchantName;

    private BigDecimal totalAmount;

    private String productSummary;

    private Integer status;

    private String statusDesc;

    private LocalDateTime createTime;
}
