package org.example.takeout.integration;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Rider.Entity.Rider;
import org.example.takeout.Rider.Mapper.RiderMapper;
import org.example.takeout.Settlement.Enums.BeneficiaryTypeEnum;
import org.example.takeout.Settlement.Enums.IncomeRecordTypeEnum;
import org.example.takeout.Settlement.Service.RewardQueryService;
import org.example.takeout.Settlement.VO.RewardSummaryVO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transaction and concurrency coverage against a randomly named, fully migrated MySQL schema.
 * Enable with -Dtakeout.settlement-test.enabled=true. Optional connection properties:
 * takeout.settlement-test.mysql.host/port/user/password (defaults: localhost/3306/root/root).
 */
@EnabledIfSystemProperty(named = "takeout.settlement-test.enabled", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "order.timeout.enabled=false",
        "delivery.geo.reconciliation.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MysqlSettlementIntegrationTest {
    private static final long USER_ID = 901L;
    private static final long MERCHANT_ID = 101L;
    private static final long RIDER_ID = 301L;
    private static final long ORDER_ID = 601L;
    private static final long TASK_ID = 501L;
    private static final LocalDateTime TASK_TIME = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final int RIDER = BeneficiaryTypeEnum.RIDER.getCode();
    private static final int MERCHANT = BeneficiaryTypeEnum.MERCHANT.getCode();
    private static final int DELIVERY_REWARD = IncomeRecordTypeEnum.DELIVERY_REWARD.getCode();
    private static final int ORDER_INCOME = IncomeRecordTypeEnum.ORDER_INCOME.getCode();
    private static final String INCOME_TRIGGER = "settlement_fail_reward_insert";
    private static final String MERCHANT_BALANCE_TRIGGER = "settlement_fail_merchant_balance";
    private static final String RIDER_BALANCE_TRIGGER = "settlement_fail_rider_balance";
    private static final String UNIQUE_CONFLICT_TRIGGER = "settlement_force_unique_conflict";
    private static TestDatabase database;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MerchantMapper merchantMapper;

    @Autowired
    private RiderMapper riderMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderService orderService;

    @Autowired
    private RewardQueryService rewardQueryService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JWTUtils jwtUtils;

    private MockMvc mockMvc;

    // Scheduled jobs are unrelated to settlement; keep their infrastructure away from this suite.
    @MockitoBean
    private org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @MockitoBean
    private org.example.takeout.Product.Config.ProductImageCleanupService productImageCleanupService;

    @MockitoBean
    private org.example.takeout.DeliveryTask.Config.DeliveryTaskGeoReconciliationJob deliveryTaskGeoReconciliationJob;

    @MockitoBean
    private org.example.takeout.Rider.Config.RiderLocationCleanupScheduler riderLocationCleanupScheduler;

    @MockitoBean
    private org.example.takeout.Merchant.Config.MerchantSseSchedule merchantSseSchedule;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = TestDatabase.create();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @BeforeEach
    void seedAccountsAndOrder() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcTemplate.execute((Connection connection) -> {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).isEqualTo(database.name());
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            return null;
        });
        dropFaultTriggers();
        jdbcTemplate.update("DELETE FROM income_record");
        jdbcTemplate.update("DELETE FROM delivery_task");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM rider");
        jdbcTemplate.update("DELETE FROM merchant");
        jdbcTemplate.update("""
                INSERT INTO merchant (id, username, phone, password, merchant_name, address, status)
                VALUES (?, 'settlement_merchant', '13900139101', 'unused-hash', 'Settlement Shop', 'Merchant Road', 1)
                """, MERCHANT_ID);
        jdbcTemplate.update("""
                INSERT INTO rider (id, name, phone, password, status, create_time, is_delete)
                VALUES (?, 'settlement_rider', '13800138301', 'unused-hash', 1, ?, 0)
                """, RIDER_ID, TASK_TIME);
        seedDeliveredOrder(ORDER_ID, TASK_ID, MERCHANT_ID, RIDER_ID,
                OrderStatusEnum.DELIVERED.getCode(), DeliveryTaskEnums.COMPLETED.getCode(),
                new BigDecimal("105.00"), new BigDecimal("12.50"), new BigDecimal("8.50"),
                new BigDecimal("6.25"));
    }

    @AfterEach
    void clearContextAndTriggers() {
        UserContextHolder.clear();
        RiderContextHolder.clear();
        MerchantContextHolder.clear();
        dropFaultTriggers();
    }

    @AfterAll
    static void dropOwnedDatabase() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void finishesOrderAndSettlesSavedAmountsExactlyOnce() {
        asUser(() -> orderService.checkedOrder(ORDER_ID));

        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.FINISHED.getCode());
        assertThat(amount(ORDER_ID, MERCHANT, MERCHANT_ID, ORDER_INCOME))
                .isEqualByComparingTo("92.50");
        assertThat(amount(ORDER_ID, RIDER, RIDER_ID, DELIVERY_REWARD))
                .isEqualByComparingTo("6.25");
        assertThat(jdbcTemplate.queryForObject("SELECT delivery_fee FROM orders WHERE id = ?",
                BigDecimal.class, ORDER_ID)).isEqualByComparingTo("8.50");
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("92.50");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("6.25");
        // 累计收入与余额由同一条结算 SQL 一起累加。
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("92.50");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("6.25");
        assertThat(transactionCount(ORDER_ID)).isEqualTo(2);
    }

    @Test
    void repeatedFinishRejectsWithoutAuditingOrCreditingExistingTransactions() {
        asUser(() -> orderService.checkedOrder(ORDER_ID));
        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在或当前状态不可确认");
        assertBalancedFinishedOrder();

        jdbcTemplate.update("UPDATE income_record SET amount = 99.00 "
                + "WHERE order_id = ? AND type = " + DELIVERY_REWARD, ORDER_ID);
        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在或当前状态不可确认");
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("92.50");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("6.25");
        assertThat(transactionCount(ORDER_ID)).isEqualTo(2);

        jdbcTemplate.update("UPDATE income_record SET beneficiary_id = ? "
                + "WHERE order_id = ? AND type = " + DELIVERY_REWARD, RIDER_ID + 99, ORDER_ID);
        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在或当前状态不可确认");
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("92.50");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("6.25");
        assertThat(transactionCount(ORDER_ID)).isEqualTo(2);

        jdbcTemplate.update("DELETE FROM income_record WHERE order_id = ? AND type = " + DELIVERY_REWARD,
                ORDER_ID);
        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在或当前状态不可确认");
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("92.50");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("6.25");
        assertThat(transactionCount(ORDER_ID)).isEqualTo(1);
    }

    @Test
    void concurrentFinishOfTheSameOrderCreditsOnlyTheWinningTransition() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeException> first = executor.submit(finishAfter(start, ORDER_ID));
            Future<RuntimeException> second = executor.submit(finishAfter(start, ORDER_ID));
            start.countDown();
            RuntimeException firstFailure = first.get(10, TimeUnit.SECONDS);
            RuntimeException secondFailure = second.get(10, TimeUnit.SECONDS);

            assertThat((firstFailure == null ? 1 : 0) + (secondFailure == null ? 1 : 0)).isEqualTo(1);
            assertThat(firstFailure == null ? secondFailure : firstFailure).isInstanceOf(BusinessException.class);
            assertBalancedFinishedOrder();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void parallelOrdersAtomicallyAccumulateOnTheSameAccounts() throws Exception {
        seedDeliveredOrder(ORDER_ID + 1, TASK_ID + 1, MERCHANT_ID, RIDER_ID,
                OrderStatusEnum.DELIVERED.getCode(), DeliveryTaskEnums.COMPLETED.getCode(),
                new BigDecimal("20.00"), new BigDecimal("2.00"), new BigDecimal("4.00"),
                new BigDecimal("3.00"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeException> first = executor.submit(finishAfter(start, ORDER_ID));
            Future<RuntimeException> second = executor.submit(finishAfter(start, ORDER_ID + 1));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("110.50");
            assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("9.25");
            assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("110.50");
            assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("9.25");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM income_record",
                    Integer.class)).isEqualTo(4);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidTaskStatusOwnershipAndTimestampsPreventSettlement() {
        jdbcTemplate.update("UPDATE delivery_task SET status = ? WHERE order_id = ?",
                DeliveryTaskEnums.DELIVERING.getCode(), ORDER_ID);
        assertRejectedAndUnchanged();

        jdbcTemplate.update("UPDATE delivery_task SET status = ?, delivered_time = NULL WHERE order_id = ?",
                DeliveryTaskEnums.COMPLETED.getCode(), ORDER_ID);
        assertRejectedAndUnchanged();

        jdbcTemplate.update("UPDATE delivery_task SET delivered_time = ?, rider_id = NULL WHERE order_id = ?",
                TASK_TIME, ORDER_ID);
        assertRejectedAndUnchanged();

        jdbcTemplate.update("UPDATE delivery_task SET rider_id = ? WHERE order_id = ?", RIDER_ID, ORDER_ID);
        jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?",
                OrderStatusEnum.DELIVERING.getCode(), ORDER_ID);
        assertRejectedAndUnchanged(OrderStatusEnum.DELIVERING.getCode());
    }

    @Test
    void wrongUserAndMissingAccountsRollbackTheWholeFinish() {
        UserContextHolder.setUserId(USER_ID + 1);
        assertThatThrownBy(() -> orderService.checkedOrder(ORDER_ID)).isInstanceOf(RuntimeException.class);
        assertOrderAndAccountsUnchanged();
        UserContextHolder.clear();

        jdbcTemplate.update("UPDATE orders SET merchant_id = ? WHERE id = ?", MERCHANT_ID + 99, ORDER_ID);
        assertRejectedAndUnchanged();
        jdbcTemplate.update("UPDATE orders SET merchant_id = ? WHERE id = ?", MERCHANT_ID, ORDER_ID);

        jdbcTemplate.update("UPDATE delivery_task SET rider_id = ? WHERE order_id = ?", RIDER_ID + 99, ORDER_ID);
        assertRejectedAndUnchanged();
    }

    @Test
    void failureOnSecondLedgerInsertRollsBackTheOrderAndFirstCredit() {
        jdbcTemplate.execute("""
                CREATE TRIGGER settlement_fail_reward_insert BEFORE INSERT ON income_record
                FOR EACH ROW BEGIN
                  IF NEW.type = %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected rider ledger failure';
                  END IF;
                END
                """.formatted(DELIVERY_REWARD));

        assertRejectedAndUnchanged();
    }

    @Test
    void riderBalanceUpdateFailureRollsBackBothLedgersAndMerchantCredit() {
        jdbcTemplate.execute("""
                CREATE TRIGGER settlement_fail_rider_balance BEFORE UPDATE ON rider
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected rider balance failure'
                """);

        assertRejectedAndUnchanged();
    }

    @Test
    void merchantBalanceUpdateFailureRollsBackTheFinish() {
        jdbcTemplate.execute("CREATE TRIGGER " + MERCHANT_BALANCE_TRIGGER + " BEFORE UPDATE ON merchant "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected merchant balance failure'");

        assertRejectedAndUnchanged();
    }

    @Test
    void existingUniqueOrderEventCannotBeSilentlyTreatedAsSuccess() {
        jdbcTemplate.update("""
                INSERT INTO income_record (beneficiary_type, beneficiary_id, order_id, type, amount, create_time)
                VALUES (?, ?, ?, ?, 6.25, ?)
                """, RIDER, RIDER_ID, ORDER_ID, DELIVERY_REWARD, TASK_TIME);

        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(ORDER_ID)).isEqualTo(1);
        assertThat(amount(ORDER_ID, RIDER, RIDER_ID, DELIVERY_REWARD)).isEqualByComparingTo("6.25");
    }

    @Test
    void databaseUniqueConstraintConflictRollsBackEarlierSettlementWrites() {
        seedDeliveredOrder(ORDER_ID + 1, TASK_ID + 1, MERCHANT_ID, RIDER_ID,
                OrderStatusEnum.DELIVERED.getCode(), DeliveryTaskEnums.COMPLETED.getCode(),
                new BigDecimal("20.00"), new BigDecimal("2.00"), new BigDecimal("4.00"),
                new BigDecimal("3.00"));
        jdbcTemplate.update("""
                INSERT INTO income_record (beneficiary_type, beneficiary_id, order_id, type, amount, create_time)
                VALUES (?, ?, ?, ?, 3.00, ?)
                """, RIDER, RIDER_ID, ORDER_ID + 1, DELIVERY_REWARD, TASK_TIME);
        jdbcTemplate.execute("""
                CREATE TRIGGER settlement_force_unique_conflict BEFORE INSERT ON income_record
                FOR EACH ROW BEGIN
                  IF NEW.type = %d THEN
                    SET NEW.order_id = 602;
                  END IF;
                END
                """.formatted(DELIVERY_REWARD));

        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(amount(ORDER_ID + 1, RIDER, RIDER_ID, DELIVERY_REWARD))
                .isEqualByComparingTo("3.00");
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
    }

    @Test
    void balanceOverflowRejectsTheCreditAndRollsBackTheOrderAndLedgers() {
        jdbcTemplate.update("UPDATE merchant SET balance = 9999999999.99 WHERE id = ?", MERCHANT_ID);

        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("9999999999.99");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("0.00");
    }

    @Test
    void riderBalanceOverflowRollsBackAnEarlierMerchantCredit() {
        jdbcTemplate.update("UPDATE rider SET balance = 9999999999.99 WHERE id = ?", RIDER_ID);

        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("9999999999.99");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("0.00");
    }

    @Test
    void merchantTotalIncomeOverflowIsRejectedInNonStrictSqlMode() {
        jdbcTemplate.update("UPDATE merchant SET total_income = 9999999999.99 WHERE id = ?", MERCHANT_ID);

        assertThat(finishOrderInNonStrictSqlMode()).isInstanceOf(BusinessException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("9999999999.99");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("0.00");
    }

    @Test
    void riderTotalIncomeOverflowRollsBackEarlierMerchantCreditInNonStrictSqlMode() {
        jdbcTemplate.update("UPDATE rider SET total_income = 9999999999.99 WHERE id = ?", RIDER_ID);

        assertThat(finishOrderInNonStrictSqlMode()).isInstanceOf(BusinessException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("9999999999.99");
    }

    @Test
    void staleAccountEntityUpdatesCannotOverwriteSettledBalanceOrTotalIncome() {
        Merchant staleMerchant = merchantMapper.selectById(MERCHANT_ID);
        Rider staleRider = riderMapper.selectById(RIDER_ID);

        assertThat(merchantMapper.creditSettledIncome(MERCHANT_ID, new BigDecimal("11.00"))).isEqualTo(1);
        assertThat(riderMapper.creditSettledIncome(RIDER_ID, new BigDecimal("2.50"))).isEqualTo(1);

        staleMerchant.setMerchantName("Updated Settlement Shop");
        staleRider.setName("Updated Settlement Rider");
        assertThat(merchantMapper.updateById(staleMerchant)).isEqualTo(1);
        assertThat(riderMapper.updateById(staleRider)).isEqualTo(1);

        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("11.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("11.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("2.50");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("2.50");
    }

    @Test
    void zeroOrderIncomeAndZeroSavedRewardAreValidNonnegativeCredits() {
        jdbcTemplate.update("UPDATE orders SET total_amount = 8.50, original_amount = 0.00, discount_amount = 0.00 "
                + "WHERE id = ?", ORDER_ID);
        jdbcTemplate.update("UPDATE delivery_task SET delivery_reward = 0.00 WHERE order_id = ?", ORDER_ID);

        asUser(() -> orderService.checkedOrder(ORDER_ID));

        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.FINISHED.getCode());
        assertThat(amount(ORDER_ID, MERCHANT, MERCHANT_ID, ORDER_INCOME)).isEqualByComparingTo("0.00");
        assertThat(amount(ORDER_ID, RIDER, RIDER_ID, DELIVERY_REWARD)).isEqualByComparingTo("0.00");
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(ORDER_ID)).isEqualTo(2);
    }

    @Test
    void inconsistentOrNegativeOrderAmountsAreRejected() {
        jdbcTemplate.update("UPDATE orders SET original_amount = 10.00, discount_amount = 10.01, "
                + "total_amount = 8.49 WHERE id = ?", ORDER_ID);
        assertRejectedAndUnchanged();

        jdbcTemplate.update("UPDATE orders SET original_amount = 105.00, discount_amount = 12.50, "
                + "total_amount = 101.00 WHERE id = ?", ORDER_ID);
        jdbcTemplate.update("UPDATE delivery_task SET delivery_reward = -0.01 WHERE order_id = ?", ORDER_ID);
        // 负数配送报酬由 income_record 的 amount >= 0 约束拒绝并整体回滚。
        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
    }

    @Test
    void rewardEndpointsReturnBalanceLedgerTotalsAndIgnoreClientAccountIds() throws Exception {
        asUser(() -> orderService.checkedOrder(ORDER_ID));
        LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay();
        jdbcTemplate.update("UPDATE income_record SET create_time = ? WHERE order_id = ?",
                todayStart.plusHours(1), ORDER_ID);
        jdbcTemplate.update("""
                INSERT INTO merchant (id, username, phone, password, merchant_name, address, status)
                VALUES (202, 'other_merchant', '13900139202', 'unused-hash', 'Other Shop', 'Other Road', 1)
                """);
        jdbcTemplate.update("""
                INSERT INTO rider (id, name, phone, password, status, create_time, is_delete)
                VALUES (302, 'other_rider', '13800138302', 'unused-hash', 1, ?, 0)
                """, TASK_TIME);
        seedDeliveredOrder(704L, 604L, 202L, 302L,
                OrderStatusEnum.DELIVERED.getCode(), DeliveryTaskEnums.COMPLETED.getCode(),
                new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("5.00"),
                new BigDecimal("3.00"));
        insertRiderEntry(302L, 704L, new BigDecimal("99.00"), todayStart.plusHours(1));
        insertMerchantEntry(704L, new BigDecimal("100.00"), todayStart.plusHours(1));

        RiderContextHolder.setRiderId(RIDER_ID);
        RewardSummaryVO riderSummary;
        try {
            riderSummary = rewardQueryService.getRiderRewards(1, 10);
        } finally {
            RiderContextHolder.clear();
        }
        assertThat(riderSummary.getBalance()).isEqualByComparingTo("6.25");
        assertThat(riderSummary.getTotalIncome()).isEqualByComparingTo("6.25");
        assertThat(riderSummary.getTodayIncome()).isEqualByComparingTo("6.25");

        mockMvc.perform(get("/rider/rewards")
                        .param("riderId", "302")
                        .param("beneficiaryId", "302")
                        .header("Authorization", bearer(RIDER_ID, AuthRole.RIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.balance").value(6.25))
                .andExpect(jsonPath("$.data.totalIncome").value(6.25))
                .andExpect(jsonPath("$.data.todayIncome").value(6.25))
                .andExpect(jsonPath("$.data.transactions.total").value(1))
                .andExpect(jsonPath("$.data.transactions.list[0].beneficiaryType").value(RIDER))
                .andExpect(jsonPath("$.data.transactions.list[0].type").value(DELIVERY_REWARD));

        mockMvc.perform(get("/merchant/rewards")
                        .param("merchantId", "202")
                        .param("beneficiaryId", "202")
                        .header("Authorization", bearer(MERCHANT_ID, AuthRole.MERCHANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.balance").value(92.50))
                .andExpect(jsonPath("$.data.totalIncome").value(92.50))
                .andExpect(jsonPath("$.data.todayIncome").value(92.50))
                .andExpect(jsonPath("$.data.transactions.total").value(1))
                .andExpect(jsonPath("$.data.transactions.list[0].beneficiaryType").value(MERCHANT))
                .andExpect(jsonPath("$.data.transactions.list[0].type").value(ORDER_INCOME));
    }

    @Test
    void rewardTodayTotalsUseShanghaiMidnightAndHalfOpenNextDayBoundary() throws Exception {
        asUser(() -> orderService.checkedOrder(ORDER_ID));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        jdbcTemplate.update("UPDATE income_record SET create_time = ? WHERE order_id = ? "
                + "AND beneficiary_type = " + RIDER, todayStart.plusHours(1), ORDER_ID);

        seedDeliveredOrder(701L, 601L, MERCHANT_ID, RIDER_ID,
                OrderStatusEnum.DELIVERED.getCode(), DeliveryTaskEnums.COMPLETED.getCode(),
                new BigDecimal("20.00"), new BigDecimal("0.00"), new BigDecimal("4.00"),
                new BigDecimal("4.00"));
        seedDeliveredOrder(702L, 602L, MERCHANT_ID, RIDER_ID,
                OrderStatusEnum.DELIVERED.getCode(), DeliveryTaskEnums.COMPLETED.getCode(),
                new BigDecimal("30.00"), new BigDecimal("0.00"), new BigDecimal("4.00"),
                new BigDecimal("4.00"));
        seedDeliveredOrder(703L, 603L, MERCHANT_ID, RIDER_ID,
                OrderStatusEnum.DELIVERED.getCode(), DeliveryTaskEnums.COMPLETED.getCode(),
                new BigDecimal("40.00"), new BigDecimal("0.00"), new BigDecimal("4.00"),
                new BigDecimal("4.00"));
        // 直接写入的历史流水模拟迁移回填；包括前面已结算的 6.25 与新增的 2.00、4.00、9.00。
        jdbcTemplate.update("UPDATE rider SET total_income = 21.25 WHERE id = ?", RIDER_ID);
        insertRiderEntry(701L, new BigDecimal("2.00"), todayStart);
        insertRiderEntry(702L, new BigDecimal("4.00"), todayStart.minusSeconds(1));
        insertRiderEntry(703L, new BigDecimal("9.00"), tomorrowStart);

        mockMvc.perform(get("/rider/rewards")
                        .header("Authorization", bearer(RIDER_ID, AuthRole.RIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(6.25))
                .andExpect(jsonPath("$.data.totalIncome").value(21.25))
                .andExpect(jsonPath("$.data.todayIncome").value(8.25))
                .andExpect(jsonPath("$.data.transactions.total").value(4));
    }

    @Test
    void rewardEndpointsRejectWrongRolesBeforeReadingAnyAccounts() throws Exception {
        mockMvc.perform(get("/rider/rewards")
                        .header("Authorization", bearer(MERCHANT_ID, AuthRole.MERCHANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/merchant/rewards")
                        .header("Authorization", bearer(RIDER_ID, AuthRole.RIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void alreadyFinishedHistoricalOrderWithoutLedgerIsRejectedWithoutSettlement() {
        jdbcTemplate.update("UPDATE orders SET status = ?, finish_time = ? WHERE id = ?",
                OrderStatusEnum.FINISHED.getCode(), TASK_TIME, ORDER_ID);

        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.FINISHED.getCode());
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(ORDER_ID)).isZero();
    }

    private void assertBalancedFinishedOrder() {
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.FINISHED.getCode());
        assertThat(transactionCount(ORDER_ID)).isEqualTo(2);
        assertThat(amount(ORDER_ID, MERCHANT, MERCHANT_ID, ORDER_INCOME)).isEqualByComparingTo("92.50");
        assertThat(amount(ORDER_ID, RIDER, RIDER_ID, DELIVERY_REWARD)).isEqualByComparingTo("6.25");
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("92.50");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("6.25");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("92.50");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("6.25");
    }

    private void assertRejectedAndUnchanged() {
        assertRejectedAndUnchanged(OrderStatusEnum.DELIVERED.getCode());
    }

    private void assertRejectedAndUnchanged(int expectedOrderStatus) {
        assertThatThrownBy(() -> asUser(() -> orderService.checkedOrder(ORDER_ID)))
                .isInstanceOf(RuntimeException.class);
        assertThat(orderStatus(ORDER_ID)).isEqualTo(expectedOrderStatus);
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("0.00");
    }

    private void assertOrderAndAccountsUnchanged() {
        assertThat(orderStatus(ORDER_ID)).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(transactionCount(ORDER_ID)).isZero();
        assertThat(balance("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(balance("rider", RIDER_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("merchant", MERCHANT_ID)).isEqualByComparingTo("0.00");
        assertThat(totalIncome("rider", RIDER_ID)).isEqualByComparingTo("0.00");
    }

    private Callable<RuntimeException> finishAfter(CountDownLatch start, long orderId) {
        return () -> {
            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    return new IllegalStateException("Timed out waiting for concurrent start");
                }
                asUser(() -> orderService.checkedOrder(orderId));
                return null;
            } catch (RuntimeException failure) {
                return failure;
            } finally {
                UserContextHolder.clear();
            }
        };
    }

    private void seedDeliveredOrder(long orderId, long taskId, long merchantId, long riderId,
                                    int orderStatus, int taskStatus,
                                    BigDecimal originalAmount, BigDecimal discountAmount,
                                    BigDecimal deliveryFee, BigDecimal deliveryReward) {
        jdbcTemplate.update("""
                INSERT INTO orders (id, order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                                    total_amount, original_amount, discount_amount, delivery_fee, status,
                                    receiver_name, receiver_phone, receiver_address, create_time,
                                    timeout_cancel_available_at, update_time)
                VALUES (?, ?, ?, ?, REPEAT('a', 64), ?, 'Settlement Shop', ?, ?, ?, ?, ?,
                        'Customer', '13800000901', 'Customer Road', ?, ?, ?)
                """, orderId, "settlement-" + orderId, USER_ID, "request-" + orderId, merchantId,
                originalAmount.add(deliveryFee).subtract(discountAmount), originalAmount, discountAmount,
                deliveryFee, orderStatus, TASK_TIME.minusHours(1), TASK_TIME, TASK_TIME);
        jdbcTemplate.update("""
                INSERT INTO delivery_task (id, order_id, rider_id, merchant_name, delivery_reward, receiver_name,
                                           status, create_time, accepted_time, delivered_time, update_time,
                                           receiver_phone, receiver_address, merchant_address, merchant_phone)
                VALUES (?, ?, ?, 'Settlement Shop', ?, 'Customer', ?, ?, ?, ?, ?,
                        '13800000901', 'Customer Road', 'Merchant Road', '13900139101')
                """, taskId, orderId, riderId, deliveryReward, taskStatus, TASK_TIME.minusMinutes(30),
                TASK_TIME.minusMinutes(20), TASK_TIME, TASK_TIME);
    }

    private void asUser(Runnable action) {
        UserContextHolder.setUserId(USER_ID);
        try {
            action.run();
        } finally {
            UserContextHolder.clear();
        }
    }

    private void insertRiderEntry(long orderId, BigDecimal amount, LocalDateTime createTime) {
        insertRiderEntry(RIDER_ID, orderId, amount, createTime);
    }

    private void insertRiderEntry(long riderId, long orderId, BigDecimal amount, LocalDateTime createTime) {
        jdbcTemplate.update("""
                INSERT INTO income_record (beneficiary_type, beneficiary_id, order_id, type, amount, create_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """, RIDER, riderId, orderId, DELIVERY_REWARD, amount, createTime);
    }

    private void insertMerchantEntry(long orderId, BigDecimal amount, LocalDateTime createTime) {
        jdbcTemplate.update("""
                INSERT INTO income_record (beneficiary_type, beneficiary_id, order_id, type, amount, create_time)
                VALUES (?, 202, ?, ?, ?, ?)
                """, MERCHANT, orderId, ORDER_INCOME, amount, createTime);
    }

    private String bearer(long id, String role) {
        return "Bearer " + jwtUtils.createToken(id, role);
    }

    private int orderStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", Integer.class, orderId);
    }

    private RuntimeException finishOrderInNonStrictSqlMode() {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String originalSqlMode = jdbcTemplate.queryForObject("SELECT @@SESSION.sql_mode", String.class);
            jdbcTemplate.update("SET SESSION sql_mode = ''");
            try {
                try {
                    asUser(() -> orderService.checkedOrder(ORDER_ID));
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
                status.setRollbackOnly();
            } finally {
                jdbcTemplate.update("SET SESSION sql_mode = ?", originalSqlMode);
            }
        });
        return failure.get();
    }

    private int transactionCount(long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM income_record WHERE order_id = ?",
                Integer.class, orderId);
    }

    private BigDecimal amount(long orderId, int beneficiaryType, long beneficiaryId, int type) {
        return jdbcTemplate.queryForObject("""
                SELECT amount FROM income_record
                WHERE order_id = ? AND beneficiary_type = ? AND beneficiary_id = ? AND type = ?
                """, BigDecimal.class, orderId, beneficiaryType, beneficiaryId, type);
    }

    private BigDecimal balance(String table, long accountId) {
        return accountAmount(table, "balance", accountId);
    }

    private BigDecimal totalIncome(String table, long accountId) {
        return accountAmount(table, "total_income", accountId);
    }

    private BigDecimal accountAmount(String table, String column, long accountId) {
        if (!"merchant".equals(table) && !"rider".equals(table)) {
            throw new IllegalArgumentException("Unsupported account table: " + table);
        }
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?",
                BigDecimal.class, accountId);
    }

    private void dropFaultTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + INCOME_TRIGGER);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + MERCHANT_BALANCE_TRIGGER);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + RIDER_BALANCE_TRIGGER);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + UNIQUE_CONFLICT_TRIGGER);
    }

    private static final class TestDatabase implements AutoCloseable {
        private static final String PREFIX = "takeout_settlement_test_";
        private final String name;
        private final String jdbcUrl;

        private TestDatabase(String name, String jdbcUrl) {
            this.name = name;
            this.jdbcUrl = jdbcUrl;
        }

        private static TestDatabase create() throws SQLException {
            String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
            validateName(name);
            TestDatabase owned = new TestDatabase(name, jdbcUrl(name));
            try (Connection connection = DriverManager.getConnection(jdbcUrl(""), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 "
                        + "COLLATE utf8mb4_0900_ai_ci");
            }
            try {
                Flyway.configure()
                        .dataSource(owned.jdbcUrl, username(), password())
                        .locations("classpath:db/migration")
                        .defaultSchema(name)
                        .schemas(name)
                        .createSchemas(false)
                        .baselineOnMigrate(false)
                        .cleanDisabled(true)
                        .load()
                        .migrate();
                return owned;
            } catch (RuntimeException failure) {
                try {
                    owned.close();
                } catch (SQLException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private String name() {
            return name;
        }

        private String jdbcUrl() {
            return jdbcUrl;
        }

        private static String jdbcUrl(String schema) {
            String host = System.getProperty("takeout.settlement-test.mysql.host", "localhost");
            String port = System.getProperty("takeout.settlement-test.mysql.port", "3306");
            if (!host.matches("[a-zA-Z0-9.\\-\\[\\]:]+") || !port.matches("[0-9]{1,5}")) {
                throw new IllegalArgumentException("Settlement test MySQL host/port contain invalid characters");
            }
            return "jdbc:mysql://" + host + ":" + port + "/" + schema
                    + "?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
        }

        private static String username() {
            return System.getProperty("takeout.settlement-test.mysql.user", "root");
        }

        private static String password() {
            return System.getProperty("takeout.settlement-test.mysql.password", "root");
        }

        private static void validateName(String name) {
            if (!name.matches(PREFIX + "[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Refusing to access an unowned settlement test database: " + name);
            }
        }

        @Override
        public void close() throws SQLException {
            validateName(name);
            try (Connection connection = DriverManager.getConnection(jdbcUrl(""), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + name + "`");
            }
        }
    }
}
