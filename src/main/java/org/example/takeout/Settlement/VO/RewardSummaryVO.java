package org.example.takeout.Settlement.VO;

import com.github.pagehelper.PageInfo;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RewardSummaryVO {
    private BigDecimal balance;
    private BigDecimal totalIncome;
    private BigDecimal todayIncome;
    private PageInfo<RewardTransactionVO> transactions;
}
