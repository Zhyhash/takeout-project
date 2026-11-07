package org.example.takeout.Merchant.Entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
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

    // 店铺取货坐标，用于把配送任务写入可接任务 GEO；未配置时任务不进入附近查询。
    private BigDecimal longitude;

    private BigDecimal latitude;

    @TableField(value = "picture")
    private String pictureURL;

    @TableField("merchant_description")
    private String description;

    private Integer status;

    /** 骑手当前可用余额。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal balance;

    /** 历史累计已结算收入；只随余额一起由结算 SQL 累加，提现等余额变动不影响该值。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal totalIncome;

    private LocalTime openingTime;
    private LocalTime closingTime;

    private LocalDateTime createTime;

    @Version
    private Integer version;
}
