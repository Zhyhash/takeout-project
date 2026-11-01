package org.example.takeout.Settlement.Entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商家与骑手的收益入账流水；当前仅记录收入，不代表真实支付。 */
@Data
@TableName("income_record")
public class IncomeRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer beneficiaryType;
    private Long beneficiaryId;
    private Long orderId;
    private Integer type;
    private BigDecimal amount;
    private LocalDateTime createTime;
}
