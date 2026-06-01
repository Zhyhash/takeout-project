package org.example.tokeout.Merchant.Entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

//NOTE:v1,将店铺和登录合二为一，一个账户只有一个店铺
@Data
public class Merchant {
    private Long id;

    private String username;

    private String password;

    private String merchantName;

    private String phone;

    private String address;

    @TableField(value = "picture")
    private String pictureURL;

    private String description;

    private Integer status;

    private LocalTime openingTime;
    private LocalTime closingTime;

    private LocalDateTime createTime;
}
