package org.example.takeout.Rider.Entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("rider")
public class Rider {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String phone;

    private String password;

    private Integer status;

    /** Available internal reward balance; writes use settlement's atomic SQL. */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal balance;

    /** 历史累计已结算收入；只随余额一起由结算 SQL 累加，提现等余额变动不影响该值。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal totalIncome;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDelete;
}
