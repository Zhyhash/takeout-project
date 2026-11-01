package org.example.takeout.Settlement.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Rider.Mapper.RiderMapper;
import org.example.takeout.Settlement.Entity.IncomeRecord;
import org.example.takeout.Settlement.Enums.BeneficiaryTypeEnum;
import org.example.takeout.Settlement.Mapper.IncomeRecordConverter;
import org.example.takeout.Settlement.Mapper.IncomeRecordMapper;
import org.example.takeout.Settlement.VO.RewardSummaryVO;
import org.example.takeout.Settlement.VO.RewardTransactionVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 读取商家或骑手本人的累计收益、今日收益、可用余额和分页收益流水。 */
@Service
@RequiredArgsConstructor
public class RewardQueryService {
    private static final ZoneId BUSINESS_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final IncomeRecordMapper incomeRecordMapper;
    private final IncomeRecordConverter incomeRecordConverter;
    private final MerchantMapper merchantMapper;
    private final RiderMapper riderMapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RewardSummaryVO getRiderRewards(Integer pageNum, Integer pageSize) {
        Long riderId = RiderContextHolder.getRiderId();
        if (riderId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "骑手身份无效");
        }
        return query(BeneficiaryTypeEnum.RIDER, riderId, pageNum, pageSize);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RewardSummaryVO getMerchantRewards(Integer pageNum, Integer pageSize) {
        Long merchantId = MerchantContextHolder.getMerchantId();
        if (merchantId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商家身份无效");
        }
        return query(BeneficiaryTypeEnum.MERCHANT, merchantId, pageNum, pageSize);
    }

    private RewardSummaryVO query(BeneficiaryTypeEnum beneficiaryType, Long beneficiaryId,
                                  Integer pageNum, Integer pageSize) {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "分页参数不合法");
        }

        // 余额和累计收入都保存在账户行上，读取后再查流水，整个事务共享同一个 REPEATABLE READ 快照。
        BigDecimal balance;
        BigDecimal totalIncome;
        if (beneficiaryType == BeneficiaryTypeEnum.RIDER) {
            balance = riderMapper.selectBalanceById(beneficiaryId);
            totalIncome = riderMapper.selectTotalIncomeById(beneficiaryId);
        } else if (beneficiaryType == BeneficiaryTypeEnum.MERCHANT) {
            balance = merchantMapper.selectBalanceById(beneficiaryId);
            totalIncome = merchantMapper.selectTotalIncomeById(beneficiaryId);
        } else {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "收益账户类型无效");
        }
        if (balance == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "收益账户不存在");
        }

        LocalDate today = LocalDate.now(BUSINESS_TIME_ZONE);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        BigDecimal todayIncome = incomeRecordMapper.selectTodayIncome(
                beneficiaryType.getCode(), beneficiaryId, todayStart, tomorrowStart);

        PageHelper.startPage(pageNum, pageSize);
        PageInfo<IncomeRecord> recordPage = new PageInfo<>(incomeRecordMapper.selectList(
                Wrappers.<IncomeRecord>lambdaQuery()
                        .eq(IncomeRecord::getBeneficiaryType, beneficiaryType.getCode())
                        .eq(IncomeRecord::getBeneficiaryId, beneficiaryId)
                        .orderByDesc(IncomeRecord::getCreateTime)
                        .orderByDesc(IncomeRecord::getId)));

        RewardSummaryVO result = new RewardSummaryVO();
        result.setBalance(balance);
        result.setTotalIncome(totalIncome);
        result.setTodayIncome(todayIncome);
        result.setTransactions(convertTransactions(recordPage));
        return result;
    }

    private PageInfo<RewardTransactionVO> convertTransactions(PageInfo<IncomeRecord> recordPage) {
        return recordPage.convert(incomeRecordConverter::toRewardTransactionVO);
    }
}
