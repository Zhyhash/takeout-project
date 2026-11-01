package org.example.takeout.Settlement.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.takeout.Settlement.Entity.IncomeRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface IncomeRecordMapper extends BaseMapper<IncomeRecord> {
    /** 上海时区当日零点（含）至次日零点（不含）的收益流水金额之和，无流水时返回 0。 */
    @Select("""
            SELECT COALESCE(SUM(amount), 0)
            FROM income_record
            WHERE beneficiary_type = #{beneficiaryType}
              AND beneficiary_id = #{beneficiaryId}
              AND create_time >= #{todayStart}
              AND create_time < #{tomorrowStart}
            """)
    BigDecimal selectTodayIncome(
            @Param("beneficiaryType") Integer beneficiaryType,
            @Param("beneficiaryId") Long beneficiaryId,
            @Param("todayStart") LocalDateTime todayStart,
            @Param("tomorrowStart") LocalDateTime tomorrowStart);
}
