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
    /** 骑手最近上报位置到商家取货点的 GEO 距离（公里）；未命中或降级时为 null。 */
    private Double pickupDistanceKm;
}
