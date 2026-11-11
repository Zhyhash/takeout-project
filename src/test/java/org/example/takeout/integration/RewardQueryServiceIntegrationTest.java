package org.example.takeout.integration;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Rider.Config.RiderLocationCleanupScheduler;
import org.example.takeout.Settlement.Service.RewardQueryService;
import org.example.takeout.Settlement.VO.RewardSummaryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RewardQueryServiceIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long RIDER_ID = 301L;
    private static final long MERCHANT_ID = 201L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RewardQueryService rewardQueryService;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @MockitoBean
    private RiderLocationCleanupScheduler riderLocationCleanupScheduler;

    @BeforeEach
    void createQuerySchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS income_record");
        jdbcTemplate.execute("DROP TABLE IF EXISTS rider");
        jdbcTemplate.execute("DROP TABLE IF EXISTS merchant");
        jdbcTemplate.execute("""
                CREATE TABLE rider (
                    id BIGINT PRIMARY KEY,
                    balance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                    total_income DECIMAL(12,2) NOT NULL DEFAULT 0.00
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE merchant (
                    id BIGINT PRIMARY KEY,
                    balance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                    total_income DECIMAL(12,2) NOT NULL DEFAULT 0.00
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE income_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    beneficiary_type TINYINT NOT NULL,
                    beneficiary_id BIGINT NOT NULL,
                    order_id BIGINT NOT NULL,
                    type TINYINT NOT NULL,
                    amount DECIMAL(12,2) NOT NULL,
                    create_time DATETIME NOT NULL
                )
                """);
        // 累计收入保存在账户行上；这里的取值模拟结算写入与迁移回填后的结果。
        jdbcTemplate.update("INSERT INTO rider (id, balance, total_income) VALUES (?, ?, ?)",
                RIDER_ID, new BigDecimal("15.00"), new BigDecimal("24.00"));
        jdbcTemplate.update("INSERT INTO merchant (id, balance, total_income) VALUES (?, ?, ?)",
                MERCHANT_ID, new BigDecimal("42.50"), new BigDecimal("20.00"));
        RiderContextHolder.setRiderId(RIDER_ID);
        MerchantContextHolder.setMerchantId(MERCHANT_ID);
    }

    @AfterEach
    void clearContexts() {
        RiderContextHolder.clear();
        MerchantContextHolder.clear();
    }

    @Test
    void totalsComeFromTheAccountRowAndTodayUsesShanghaiHalfOpenDay() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime tomorrow = today.plusDays(1).atStartOfDay();
        addIncomeRecord(1, RIDER_ID, 701L, 1, "5.00", start);
        addIncomeRecord(1, RIDER_ID, 702L, 1, "7.50", start.plusHours(3));
        addIncomeRecord(1, RIDER_ID, 703L, 1, "2.50", start.minusSeconds(1));
        addIncomeRecord(1, RIDER_ID, 704L, 1, "9.00", tomorrow);
        addIncomeRecord(1, 999L, 705L, 1, "100.00", start);
        addIncomeRecord(2, MERCHANT_ID, 706L, 2, "20.00", start);

        RewardSummaryVO summary = rewardQueryService.getRiderRewards(1, 1);

        assertThat(summary.getBalance()).isEqualByComparingTo("15.00");
        assertThat(summary.getTotalIncome()).isEqualByComparingTo("24.00");
        assertThat(summary.getTodayIncome()).isEqualByComparingTo("12.50");
        assertThat(summary.getTransactions().getTotal()).isEqualTo(4L);
        assertThat(summary.getTransactions().getList()).hasSize(1);
        assertThat(summary.getTransactions().getList().get(0).getOrderId()).isEqualTo(704L);

        RewardSummaryVO merchant = rewardQueryService.getMerchantRewards(1, 10);
        assertThat(merchant.getBalance()).isEqualByComparingTo("42.50");
        assertThat(merchant.getTotalIncome()).isEqualByComparingTo("20.00");
        assertThat(merchant.getTodayIncome()).isEqualByComparingTo("20.00");
    }

    @Test
    void queryRequiresTheMatchingLoginContext() {
        RiderContextHolder.clear();
        assertThatThrownBy(() -> rewardQueryService.getRiderRewards(1, 10))
                .isInstanceOf(RuntimeException.class);

        MerchantContextHolder.clear();
        assertThatThrownBy(() -> rewardQueryService.getMerchantRewards(1, 10))
                .isInstanceOf(RuntimeException.class);
    }

    private void addIncomeRecord(int beneficiaryType, long beneficiaryId, long orderId,
                                 int type, String amount, LocalDateTime createTime) {
        jdbcTemplate.update("""
                INSERT INTO income_record
                    (beneficiary_type, beneficiary_id, order_id, type, amount, create_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """, beneficiaryType, beneficiaryId, orderId, type, new BigDecimal(amount), createTime);
    }
}
