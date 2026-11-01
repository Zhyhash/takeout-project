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
     * 客户端下单请求唯一标识
     */
    private String requestId;

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
     * 配送费
     */
    private BigDecimal deliveryFee;

    /**
     * 订单状态
     */
    private Integer status;

    /**
     * 订单取消来源
     * 1：用户主动取消，2：商家拒单，3：系统超时取消，4：平台取消
     */
    private Integer cancelSource;

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

    /**
     * 订单DTO hash
     */
    private String requestHash;

    /**
     * 定时任务下一次执行时间
     */
    private LocalDateTime timeoutCancelAvailableAt;

    /**
     * 商家是否查看过这个订单
     */

    private Boolean merchantSeen;

}
