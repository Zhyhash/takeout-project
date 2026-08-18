package org.example.takeout.Product.Entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Product {
    private Long id;
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private Long categoryId;


    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private String productName;


    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private String imageUrl;
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private BigDecimal price;
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private Integer stock;

    private Long merchantId;

    @TableLogic
    private Integer isDeleted;

    private Integer status;
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime; // 新增：创建时间

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime; // 新增：更新时间

    @Version
    private Integer version;
}
