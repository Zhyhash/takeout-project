package org.example.takeout.Order.Entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders") // 表名根据实际情况修改
public class Order {

    @TableId(type = IdType.AUTO) // 或 AUTO，根据主键生成策略调整
    private Long id;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 商家ID
     */
    private Long merchantId;
    private String merchantName;

    /**
     * 订单总金额
     */

    private BigDecimal totalAmount;

    /**
     * 订单状态
     */
    private Integer status;

    /**
     * 收货人姓名
     */
    private String receiverName;

    /**
     * 收货人电话
     */
    private String receiverPhone;

    /**
     * 收货地址
     */
    private String receiverAddress;

    /**
     * 订单备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     * MyBatis-Plus 自动填充：插入和更新时自动赋值
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 完成时间
     */
    private LocalDateTime finishTime;

    /**
     * 原始金额
     */
    private BigDecimal originalAmount;

    /**
     * 优惠金额
     */
    private BigDecimal discountAmount;

    /**
     * 支付时间
     */

    private LocalDateTime payTime;

    @Version
    private Integer version;
}
