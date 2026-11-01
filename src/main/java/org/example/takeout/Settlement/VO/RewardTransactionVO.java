package org.example.takeout.Settlement.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RewardTransactionVO {
    private Long id;
    private Integer beneficiaryType;
    private Long orderId;
    private Integer type;
    private BigDecimal amount;
    private LocalDateTime createTime;
}
