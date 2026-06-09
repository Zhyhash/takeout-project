package org.example.takeout.Cart.Entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
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

    private Date createTime;
    private Date updateTime;
}
