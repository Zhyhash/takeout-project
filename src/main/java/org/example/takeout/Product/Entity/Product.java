package org.example.takeout.Product.Entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Product {
    private Long id;
    private Long categoryId;


    private String productName;


    private String imageUrl;
    private BigDecimal price;
    private Integer stock;

    private Long merchantId;
    @TableLogic
    private Integer isDeleted;
    private Integer status;
    private String description;

    private LocalDateTime createTime; // 新增：创建时间

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime; // 新增：更新时间
}
