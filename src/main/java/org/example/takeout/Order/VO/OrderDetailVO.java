package org.example.takeout.Order.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
//返回用户详细订单
@Data
public class OrderDetailVO {

    private Long id;

    private String orderNo;

    // 商家信息
    private Long merchantId;
    private String merchantName;

    // 收货信息
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;

    // 金额信息
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;

    // 状态
    private Integer status;

    // 时间
    private LocalDateTime createTime;

    // 备注
    private String remark;

    // 商品列表
    private List<OrderItemVO> items;
}

