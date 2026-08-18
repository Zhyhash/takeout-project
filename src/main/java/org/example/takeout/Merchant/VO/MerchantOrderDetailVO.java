package org.example.takeout.Merchant.VO;

import lombok.Data;
import org.example.takeout.Order.VO.OrderItemVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家查看自身订单时使用的详情视图。
 */
@Data
public class MerchantOrderDetailVO {

    private Long orderId;

    private String orderNo;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private BigDecimal originalAmount;

    private BigDecimal discountAmount;

    private BigDecimal totalAmount;

    private Integer status;

    private String statusDesc;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private String remark;

    private List<OrderItemVO> items;
}
