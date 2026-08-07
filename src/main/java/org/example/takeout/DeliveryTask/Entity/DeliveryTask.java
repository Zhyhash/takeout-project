package org.example.takeout.DeliveryTask.Entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("delivery_task")
public class DeliveryTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long riderId;

    private String merchantName;

    private BigDecimal deliveryReward;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private LocalDateTime acceptedTime;

    private LocalDateTime deliveredTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private String receiverPhone;

    private String receiverAddress;

    private String merchantAddress;

    private String merchantPhone;

    private String receiverName;

}
