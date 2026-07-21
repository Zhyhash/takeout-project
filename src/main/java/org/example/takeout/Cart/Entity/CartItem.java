package org.example.takeout.Cart.Entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
@TableName(value = "cart")
public class CartItem {

    @TableId
    private Long id;

    private Long userId;
    private Long productId;
    private Long merchantId;

    private Integer quantity;

    private String productName;
    private String productImage;

    private BigDecimal price;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @Version
    private Integer version;
}
