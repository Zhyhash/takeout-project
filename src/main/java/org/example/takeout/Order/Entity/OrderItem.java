package org.example.takeout.Order.Entity;

import lombok.Data;

import com.baomidou.mybatisplus.annotation.*;

import java.math.BigDecimal;

@Data
@TableName("order_item") // 表名根据实际情况修改
public class OrderItem {

    @TableId(type = IdType.AUTO) // 主键生成策略，可根据需求改为 AUTO
    private Long id;

    /**
     * 所属订单ID
     */
    private Long orderId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 下单时商品名
     */
    private String productName;

    /**
     * 下单时单价
     */
    private BigDecimal productPrice;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 商品小计金额
     */
    private BigDecimal subtotal;

    /**
     * 商品图片
     */
    private String productPicture;
}
