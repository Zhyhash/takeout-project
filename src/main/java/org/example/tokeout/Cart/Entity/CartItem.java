package org.example.tokeout.Cart.Entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
@Data
public class CartItem {

    //TODO:记得添加mybatis的@TableName
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
