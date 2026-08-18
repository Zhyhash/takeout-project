package org.example.takeout.DeliveryTask.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RiderTaskListVO {
    private Long taskId;
    private String merchantName;
    private String  merchantAddress;
    private String receiverAddress;
    private BigDecimal deliveryReward;
    private Integer status;
    private String statusDesc;
    private LocalDateTime createTime;
    //预留位，商家/用户距离
}
