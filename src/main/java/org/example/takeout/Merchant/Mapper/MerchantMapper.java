package org.example.takeout.Merchant.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Merchant.Entity.Merchant;

import java.math.BigDecimal;

@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {
    @Select("SELECT balance FROM merchant WHERE id = #{id}")
    BigDecimal selectBalanceById(@Param("id") Long id);

    @Select("SELECT total_income FROM merchant WHERE id = #{id}")
    BigDecimal selectTotalIncomeById(@Param("id") Long id);

    @Update("""
            UPDATE merchant SET balance = balance + #{amount},
                                total_income = total_income + #{amount}
            WHERE id = #{id}
              AND balance <= 9999999999.99 - #{amount}
              AND total_income <= 9999999999.99 - #{amount}
            """)
    int creditSettledIncome(@Param("id") Long id, @Param("amount") BigDecimal amount);
}
