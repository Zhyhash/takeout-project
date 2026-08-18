package org.example.takeout.DeliveryTask.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RiderDeliveryDetailVO {
    private Long orderId;

    private String merchantName;
    private String  merchantAddress;
    private String merchantPhone;

    private String receiverAddress;
    private String  receiverPhone;
    private String receiverName;

    private BigDecimal deliveryReward;

    private Integer status;
    private String statusDesc;

    private LocalDateTime acceptedTime;
}
