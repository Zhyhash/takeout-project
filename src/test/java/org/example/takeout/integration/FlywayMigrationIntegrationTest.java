package org.example.takeout.integration;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.example.takeout.Merchant.DTO.MerchantLoginDTO;
import org.example.takeout.Rider.DTO.RiderLoginDTO;
import org.example.takeout.User.DTO.LoginDTO;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in MySQL tests of the SQL migrations, independent of application startup.
 * Every test owns a randomly named database; existing application/test databases
 * are never selected, initialized, cleaned or repaired.
 *
 * Enable with -Dtakeout.migration-test.enabled=true. Optional connection settings:
 * takeout.migration-test.mysql.host/port/user/password (localhost/3306/root/root).
 */
@EnabledIfSystemProperty(named = "takeout.migration-test.enabled", matches = "true")
class FlywayMigrationIntegrationTest {

    private static final String BASELINE_VERSION = "20261008";
    private static final String VERSION_14 = "20261014";
    private static final String LATEST_VERSION = "20261109";
    private static final String HISTORICAL_TIME = "2024-01-02 03:04:05";
    private static final List<String> BUSINESS_TABLES = List.of(
            "cache_invalidation_task", "cart", "cart_header", "category", "delivery_task",
            "income_record", "merchant", "order_item", "orders", "product", "rider", "user");

    @Test
    void stripeRecoveryMigrationProtectsLegacyAttemptsAndKeepsUnknownCreationForReview() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.baselinedFlyway("20261030.2").migrate();
            insertMerchant(database, 10, 7);
            insertUser(database, 1);
            database.execute("INSERT INTO orders (id, order_no, user_id, merchant_id, merchant_name, total_amount, "
                    + "status, receiver_name, receiver_phone, receiver_address, request_id, request_hash) "
                    + "VALUES (60, 'stripe-recovery', 1, 10, 'merchant', 12.50, 0, "
                    + "'receiver', '13800000001', 'address', 'stripe-recovery', REPEAT('a', 64))");
            database.execute("INSERT INTO order_stripe_payments "
                    + "(order_id, amount_minor, currency, idempotency_key, create_time) "
                    + "VALUES (60, 1250, 'cny', 'original-recovery-key', NOW() - INTERVAL 2 DAY)");
            // 这个用例验证 V20261105 本身的行为，因此目标版本固定在该版本，
            // 不随后续删除租约列的迁移一起前进。
            Flyway flyway = database.flyway("20261105", false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(database.scalar("SELECT status FROM orders WHERE id = 60")).isEqualTo("1");
            assertThat(database.scalar("SELECT pay_time FROM orders WHERE id = 60")).isNull();
            assertThat(database.scalar("SELECT payment_started_at FROM orders WHERE id = 60")).isNotNull();
            assertThat(database.rows("SELECT reconciliation_state, last_error, idempotency_key, next_retry_at "
                    + "FROM order_stripe_payments WHERE order_id = 60"))
                    .containsExactly(row("REVIEW", "CREATE_RESULT_UNKNOWN_IDEMPOTENCY_EXPIRED", "original-recovery-key", null));
            assertThatThrownBy(() -> database.execute("UPDATE order_stripe_payments SET reconciliation_state = 'succeeded'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> database.execute("UPDATE order_stripe_payments SET retry_count = -1"))
                    .isInstanceOf(SQLException.class);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void stripeRecoveryMigrationStopsBeforeChangingOrphanPayingOrders() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.baselinedFlyway("20261030.2").migrate();
            insertMerchant(database, 10, 7);
            insertUser(database, 1);
            database.execute("INSERT INTO orders (id, order_no, user_id, merchant_id, merchant_name, total_amount, "
                    + "status, receiver_name, receiver_phone, receiver_address, request_id, request_hash) "
                    + "VALUES (60, 'orphan-paying', 1, 10, 'merchant', 12.50, 1, "
                    + "'receiver', '13800000001', 'address', 'orphan-paying', REPEAT('a', 64))");
            assertThatThrownBy(() -> database.flyway(LATEST_VERSION, false, BASELINE_VERSION).migrate())
                    .isInstanceOf(FlywayException.class).hasStackTraceContaining("chk_no_orphan_paying_orders");
            assertThat(database.scalar("SELECT status FROM orders WHERE id = 60")).isEqualTo("1");
            assertThat(database.scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                    + "AND table_name = 'orders' AND column_name = 'payment_started_at'")).isEqualTo("0");
        }
    }

    @Test
    void stripePaymentMigrationKeepsOrdersAndAddsDurableUniqueReferences() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.baselinedFlyway("20261030").migrate();
            insertMerchant(database, 10, 7);
            insertUser(database, 1);
            database.execute("INSERT INTO orders (id, order_no, user_id, merchant_id, merchant_name, total_amount, "
                    + "status, receiver_name, receiver_phone, receiver_address, request_id, request_hash) "
                    + "VALUES (60, 'stripe-order', 1, 10, 'merchant', 12.50, 0, "
                    + "'receiver', '13800000001', 'address', 'stripe-request', REPEAT('a', 64))");
            List<List<String>> before = database.rows("SELECT * FROM orders WHERE id = 60");
            Flyway flyway = database.flyway("20261030.1", false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(database.rows("SELECT * FROM orders WHERE id = 60")).isEqualTo(before);
            database.execute("INSERT INTO order_stripe_payments "
                    + "(order_id, payment_intent_id, amount_minor, currency, idempotency_key) "
                    + "VALUES (60, 'pi_migration_test', 1250, 'cny', 'migration-idempotency')");
            assertThatThrownBy(() -> database.execute("INSERT INTO order_stripe_payments "
                    + "(order_id, amount_minor, currency, idempotency_key) "
                    + "VALUES (60, 1250, 'cny', 'other-key')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> database.execute("UPDATE order_stripe_payments SET currency = 'usd' "
                    + "WHERE order_id = 60")).isInstanceOf(SQLException.class);
            database.execute("INSERT INTO stripe_payment_events (event_id, payment_intent_id, event_type) "
                    + "VALUES ('evt_migration_test', 'pi_migration_test', 'payment_intent.succeeded')");
            assertThatThrownBy(() -> database.execute("INSERT INTO stripe_payment_events "
                    + "(event_id, payment_intent_id, event_type) "
                    + "VALUES ('evt_migration_test', 'pi_migration_test', 'payment_intent.succeeded')"))
                    .isInstanceOf(SQLException.class);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void stripeRefundMigrationUpgradesPreviousLatestAndEnforcesRefundIdentityAndUpdateTime() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.baselinedFlyway("20261106.1").migrate();
            assertThat(database.scalar("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'order_stripe_refunds'"))
                    .isEqualTo("0");

            Flyway refundFlyway = database.flyway("20261108", false, BASELINE_VERSION);
            assertThat(refundFlyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(database.scalar("SELECT COUNT(*) FROM order_stripe_refunds")).isEqualTo("0");
            assertThat(database.column("order_stripe_refunds", "refund_id", "is_nullable")).isEqualTo("YES");
            assertThat(database.column("order_stripe_refunds", "refund_id", "collation_name"))
                    .isEqualTo("ascii_bin");
            assertThat(database.column("order_stripe_refunds", "idempotency_key", "collation_name"))
                    .isEqualTo("ascii_bin");
            assertThat(database.column("order_stripe_refunds", "reconciliation_state", "column_default"))
                    .isNull();
            assertThat(database.column("order_stripe_refunds", "update_time", "extra"))
                    .containsIgnoringCase("on update CURRENT_TIMESTAMP");

            database.execute("INSERT INTO order_stripe_refunds "
                    + "(order_id, refund_id, amount_minor, currency, idempotency_key, reconciliation_state, "
                    + "create_time, update_time) VALUES "
                    + "(60, NULL, 1250, 'cny', 'refund-key-60', 'PENDING', '" + HISTORICAL_TIME + "', '"
                    + HISTORICAL_TIME + "'), "
                    + "(61, NULL, 500, 'cny', 'refund-key-61', 'PENDING', '" + HISTORICAL_TIME + "', '"
                    + HISTORICAL_TIME + "'), "
                    + "(62, 're_migration_test', 250, 'cny', 'refund-key-62', 'PENDING', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            assertThatThrownBy(() -> database.execute("INSERT INTO order_stripe_refunds "
                    + "(order_id, refund_id, amount_minor, currency, idempotency_key, reconciliation_state) "
                    + "VALUES (63, 're_migration_test', 250, 'cny', 'refund-key-63', 'PENDING')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> database.execute("INSERT INTO order_stripe_refunds "
                    + "(order_id, amount_minor, currency, idempotency_key, reconciliation_state) "
                    + "VALUES (64, 250, 'cny', 'refund-key-60', 'PENDING')"))
                    .isInstanceOf(SQLException.class);

            Flyway flyway = database.flyway(LATEST_VERSION, false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(LATEST_VERSION);
            assertStripeRefundRecoveryDefinitions(database);
            assertThat(database.rows("SELECT order_id, retry_count, next_retry_at "
                    + "FROM order_stripe_refunds ORDER BY order_id"))
                    .containsExactly(row("60", "0", null), row("61", "0", null), row("62", "0", null));
            assertThat(database.scalar("SELECT COUNT(*) FROM order_stripe_refunds "
                    + "WHERE reconciliation_state = 'PENDING' "
                    + "AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP)"))
                    .isEqualTo("3");

            database.execute("UPDATE order_stripe_refunds SET stripe_status = 'succeeded' WHERE order_id = 60");
            assertThat(database.rows("SELECT DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s'), "
                    + "update_time > create_time FROM order_stripe_refunds "
                    + "WHERE order_id = 60"))
                    .containsExactly(row(HISTORICAL_TIME, "1"));
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void upgradesActualLocalSchemaAndHistoricalDataWithoutLosingValues() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            insertMerchant(database, 10, 7);
            insertUser(database, 1);
            insertUser(database, 2);
            insertUser(database, 3);
            insertUser(database, 4);
            database.execute("INSERT INTO orders (id, order_no, user_id, merchant_id, merchant_name, total_amount, "
                    + "status, receiver_name, receiver_phone, receiver_address, request_id, request_hash, update_time) "
                    + "VALUES (60, 'historical_order', 1, 10, 'historical merchant', 12.50, 0, "
                    + "'receiver', '13800000001', 'historical address', 'historical_request', REPEAT('a', 64), '"
                    + HISTORICAL_TIME + "')");
            database.execute("INSERT INTO category (id, merchant_id, category_name) VALUES (20, 10, 'historical')");
            database.execute("INSERT INTO product (id, category_id, product_name, price, merchant_id, stock, version) "
                    + "VALUES (30, 20, 'historical product', 12.50, 10, 9, 4)");
            insertCart(database, 1, 30, 10);
            insertCart(database, 3, 31, 10);
            database.execute("INSERT INTO cart_header (user_id, merchant_id) VALUES (2, 99), (3, 99)");
            insertRider(database, 41);
            insertDeliveryTask(database, 51, 41L);
            Map<String, List<List<String>>> before = database.businessRows();

            Flyway flyway = database.baselinedFlyway(LATEST_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(18);

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(LATEST_VERSION);
            assertThat(database.rows("SELECT user_id, merchant_id FROM cart_header ORDER BY user_id"))
                    .containsExactly(row("1", "10"), row("2", null), row("3", "10"), row("4", null));
            assertThat(database.scalar("SELECT delivery_reward FROM delivery_task WHERE id = 51"))
                    .isEqualTo("5.00");
            assertThat(database.scalar("SELECT delivery_fee FROM orders WHERE id = 60"))
                    .isEqualTo("0.00");
            assertThat(database.scalar("SELECT DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') "
                    + "FROM orders WHERE id = 60")).isEqualTo(HISTORICAL_TIME);
            assertThat(database.scalar("SELECT DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') "
                    + "FROM delivery_task WHERE id = 51")).isEqualTo(HISTORICAL_TIME);
            assertThat(database.scalar("SELECT version FROM merchant WHERE id = 10")).isEqualTo("7");
            assertThat(database.scalar("SELECT balance FROM merchant WHERE id = 10")).isEqualTo("0.00");
            assertThat(database.scalar("SELECT total_income FROM merchant WHERE id = 10")).isEqualTo("0.00");
            assertThat(database.scalar("SELECT balance FROM rider WHERE id = 41")).isEqualTo("0.00");
            assertThat(database.scalar("SELECT total_income FROM rider WHERE id = 41")).isEqualTo("0.00");
            assertThat(database.scalar("SELECT COUNT(*) FROM income_record")).isEqualTo("0");
            assertThat(database.rows("SELECT category_id, stock, version FROM product WHERE id = 30"))
                    .containsExactly(row("20", "9", "4"));
            assertThat(database.rows("SELECT id, order_id, rider_id, merchant_name, status, create_time, "
                    + "accepted_time, delivered_time, update_time, receiver_address, receiver_phone, "
                    + "merchant_address, merchant_phone, receiver_name FROM delivery_task ORDER BY id"))
                    .isEqualTo(before.get("delivery_task"));
            for (String table : List.of("user", "cart", "rider", "category", "merchant", "product",
                    "cache_invalidation_task", "orders", "order_item")) {
                // Compare only columns that existed before the upgrade; later migrations add columns.
                assertThat(database.rows("SELECT " + baselineSelectColumns(table)
                                + " FROM `" + table + "` ORDER BY 1"))
                        .as("historical rows in %s", table).isEqualTo(before.get(table));
            }
            assertThat(database.rows("SELECT stock_version FROM product WHERE id = 30"))
                    .containsExactly(row("0"));
            assertThat(database.scalar("SELECT COUNT(*) FROM order_item WHERE stock_version <> 0"))
                    .isEqualTo("0");
            assertLatestColumnDefinitions(database);

            insertDefaultedMerchant(database);
            assertThat(database.scalar("SELECT version FROM merchant WHERE username = 'defaulted_merchant'"))
                    .isEqualTo("0");
            database.execute("INSERT INTO product (category_id, product_name, price, merchant_id) "
                    + "VALUES (20, 'defaulted product', 1.00, 10)");
            assertThat(database.scalar("SELECT stock FROM product WHERE product_name = 'defaulted product'"))
                    .isEqualTo("0");
            assertThatThrownBy(() -> database.execute("INSERT INTO product "
                    + "(category_id, product_name, price, merchant_id) VALUES (NULL, 'invalid product', 1.00, 10)"))
                    .isInstanceOf(SQLException.class);

            database.execute("INSERT INTO income_record "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount) VALUES "
                    + "(2, 10, 60, 2, 12.50), "
                    + "(1, 41, 60, 1, 5.00)");
            assertThatThrownBy(() -> database.execute("INSERT INTO income_record "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount) "
                    + "VALUES (2, 11, 60, 2, 12.50)"))
                    .as("one order event cannot be credited to a second beneficiary")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> database.execute("INSERT INTO income_record "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount) "
                    + "VALUES (2, 10, 60, 1, 5.00)"))
                    .as("income event type must match the beneficiary kind")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> database.execute("INSERT INTO income_record "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount) "
                    + "VALUES (1, 41, 60, 1, -0.01)"))
                    .as("income amounts cannot be negative")
                    .isInstanceOf(SQLException.class);

            database.execute("INSERT INTO rider (name, phone, password, status, create_time, is_delete) "
                    + "VALUES ('generated rider', '13800000999', 'hash', 1, '" + HISTORICAL_TIME + "', 0)");
            assertThat(Long.parseLong(database.scalar("SELECT id FROM rider WHERE name = 'generated rider'")))
                    .isGreaterThan(41);
            database.execute("INSERT INTO delivery_task (order_id, merchant_name, delivery_reward, status, "
                    + "create_time, receiver_address, receiver_phone, merchant_address, merchant_phone, receiver_name) "
                    + "VALUES (1002, 'historical merchant', 6.25, 0, '" + HISTORICAL_TIME
                    + "', 'receiver address', '13800000001', 'merchant address', '13800000010', 'receiver')");
            assertThat(Long.parseLong(database.scalar("SELECT id FROM delivery_task WHERE order_id = 1002")))
                    .isGreaterThan(51);

            Map<String, List<List<String>>> migratedRows = database.businessRows();
            List<List<String>> migratedColumns = database.columnDefinitions();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(database.businessRows()).isEqualTo(migratedRows);
            assertThat(database.columnDefinitions()).isEqualTo(migratedColumns);
        }
    }

    @Test
    void incomeEncodingMigrationConvertsHistoricalStringCodesAndBackfillsTotalIncome() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            prepareIncomeEncodingMigration(database);
            insertSettlementOrder(database, 60);
            insertSettlementOrder(database, 61);
            insertSettlementOrder(database, 62);
            database.execute("UPDATE merchant SET balance = 100.00 WHERE id = 10");
            database.execute("UPDATE rider SET balance = 40.00 WHERE id = 10");
            database.execute("INSERT INTO account_transaction "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount, create_time) VALUES "
                    + "('MERCHANT', 10, 60, 'ORDER_INCOME', 12.50, '" + HISTORICAL_TIME + "'), "
                    + "('MERCHANT', 10, 61, 'ORDER_INCOME', 7.25, '2024-02-03 04:05:06'), "
                    + "('RIDER', 10, 60, 'DELIVERY_REWARD', 5.00, '" + HISTORICAL_TIME + "'), "
                    + "('RIDER', 10, 62, 'DELIVERY_REWARD', 6.75, '2024-03-04 05:06:07')");

            Flyway flyway = database.flyway(LATEST_VERSION, false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);

            // 历史字符串编码按显式值映射转换为整数，而不是按枚举 ordinal。
            assertThat(database.rows("SELECT beneficiary_type, beneficiary_id, order_id, type, amount, "
                    + "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') FROM income_record "
                    + "ORDER BY beneficiary_type, order_id"))
                    .containsExactly(
                            row("1", "10", "60", "1", "5.00", HISTORICAL_TIME),
                            row("1", "10", "62", "1", "6.75", "2024-03-04 05:06:07"),
                            row("2", "10", "60", "2", "12.50", HISTORICAL_TIME),
                            row("2", "10", "61", "2", "7.25", "2024-02-03 04:05:06"));
            assertThat(database.scalar("SELECT total_income FROM merchant WHERE id = 10")).isEqualTo("19.75");
            assertThat(database.scalar("SELECT total_income FROM rider WHERE id = 10")).isEqualTo("11.75");
            assertThat(database.scalar("SELECT balance FROM merchant WHERE id = 10")).isEqualTo("100.00");
            assertThat(database.scalar("SELECT balance FROM rider WHERE id = 10")).isEqualTo("40.00");
            assertThat(database.scalar("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'account_transaction'"))
                    .isEqualTo("0");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void incomeEncodingMigrationMergesMatchingEventsWhenBothLedgersExist() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            prepareIncomeEncodingMigration(database);
            insertSettlementOrder(database, 60);
            insertSettlementOrder(database, 61);
            database.execute("UPDATE merchant SET balance = 100.00 WHERE id = 10");
            database.execute("UPDATE rider SET balance = 40.00 WHERE id = 10");
            createIncomeRecordTable(database);
            database.execute("INSERT INTO income_record "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount, create_time) "
                    + "VALUES (2, 10, 60, 2, 12.50, '" + HISTORICAL_TIME + "')");
            database.execute("INSERT INTO account_transaction "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount, create_time) VALUES "
                    + "('MERCHANT', 10, 60, 'ORDER_INCOME', 12.50, '2024-02-03 04:05:06'), "
                    + "('MERCHANT', 10, 61, 'ORDER_INCOME', 7.25, '2024-02-03 04:05:06'), "
                    + "('RIDER', 10, 60, 'DELIVERY_REWARD', 5.00, '" + HISTORICAL_TIME + "')");

            Flyway flyway = database.flyway(LATEST_VERSION, false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);

            assertThat(database.rows("SELECT beneficiary_type, beneficiary_id, order_id, type, amount, "
                    + "DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') FROM income_record "
                    + "ORDER BY beneficiary_type, order_id"))
                    .containsExactly(
                            row("1", "10", "60", "1", "5.00", HISTORICAL_TIME),
                            row("2", "10", "60", "2", "12.50", HISTORICAL_TIME),
                            row("2", "10", "61", "2", "7.25", "2024-02-03 04:05:06"));
            assertThat(database.scalar("SELECT total_income FROM merchant WHERE id = 10")).isEqualTo("19.75");
            assertThat(database.scalar("SELECT total_income FROM rider WHERE id = 10")).isEqualTo("5.00");
            assertThat(database.scalar("SELECT balance FROM merchant WHERE id = 10")).isEqualTo("100.00");
            assertThat(database.scalar("SELECT balance FROM rider WHERE id = 10")).isEqualTo("40.00");
            assertThat(database.scalar("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'account_transaction'"))
                    .isEqualTo("0");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void incomeEncodingMigrationRetainsLegacyLedgerWhenExistingEventConflicts() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            prepareIncomeEncodingMigration(database);
            insertSettlementOrder(database, 60);
            createIncomeRecordTable(database);
            database.execute("INSERT INTO income_record "
                    + "(id, beneficiary_type, beneficiary_id, order_id, type, amount, create_time) "
                    + "VALUES (100, 2, 10, 60, 2, 99.00, '" + HISTORICAL_TIME + "')");
            database.execute("INSERT INTO account_transaction "
                    + "(beneficiary_type, beneficiary_id, order_id, type, amount, create_time) "
                    + "VALUES ('MERCHANT', 10, 60, 'ORDER_INCOME', 12.50, '" + HISTORICAL_TIME + "')");
            List<List<String>> legacyRows = database.rows("SELECT id, beneficiary_type, beneficiary_id, order_id, "
                    + "type, amount FROM account_transaction ORDER BY id");

            assertThatThrownBy(() -> database.flyway(LATEST_VERSION, false, BASELINE_VERSION).migrate())
                    .isInstanceOf(FlywayException.class);

            assertThat(database.rows("SELECT id, beneficiary_type, beneficiary_id, order_id, type, amount "
                    + "FROM account_transaction ORDER BY id")).isEqualTo(legacyRows);
            assertThat(database.scalar("SELECT amount FROM income_record WHERE order_id = 60 AND type = 2"))
                    .isEqualTo("99.00");
            assertThat(database.scalar("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'account_transaction'"))
                    .isEqualTo("1");
        }
    }

    @Test
    void fillsOnlyNullRewardsAndPreservesExistingRewardsAndUpdateTimes() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.execute("ALTER TABLE delivery_task ADD COLUMN delivery_reward DECIMAL(10,2) NULL");
            // An older deployment may automatically update this timestamp on row updates.
            database.execute("ALTER TABLE delivery_task MODIFY update_time DATETIME NULL "
                    + "DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP");
            insertDeliveryTask(database, 51, null);
            insertDeliveryTask(database, 52, null);
            insertDeliveryTask(database, 53, null);
            database.execute("UPDATE delivery_task SET delivery_reward = 8.75, update_time = '"
                    + HISTORICAL_TIME + "' WHERE id = 52");
            database.execute("UPDATE delivery_task SET update_time = NULL WHERE id = 53");

            assertThat(database.baselinedFlyway(LATEST_VERSION).migrate().migrationsExecuted).isEqualTo(18);

            assertThat(database.rows("SELECT id, delivery_reward, DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') "
                    + "FROM delivery_task ORDER BY id"))
                    .containsExactly(
                            row("51", "5.00", HISTORICAL_TIME),
                            row("52", "8.75", HISTORICAL_TIME),
                            row("53", "5.00", null));
            assertThat(database.column("delivery_task", "delivery_reward", "is_nullable")).isEqualTo("NO");
        }
    }

    @Test
    void deliveryFeeMigrationBackfillsNullsAndPreservesExistingFeeSnapshots() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.execute("ALTER TABLE orders ADD COLUMN delivery_fee DECIMAL(10, 2) NULL COMMENT '配送费'");
            database.baselinedFlyway("20261027").migrate();
            insertUser(database, 1);
            insertMerchant(database, 10, 7);
            database.execute("INSERT INTO orders (id, order_no, user_id, merchant_id, merchant_name, total_amount, "
                    + "delivery_fee, status, receiver_name, receiver_phone, receiver_address, request_id, request_hash, "
                    + "update_time) "
                    + "VALUES (90, 'fee-existing', 1, 10, 'merchant', 15.00, 8.75, 0, 'receiver', "
                    + "'13800000001', 'address', 'fee-existing-request', REPEAT('a', 64), '" + HISTORICAL_TIME + "'), "
                    + "(91, 'fee-null', 1, 10, 'merchant', 15.00, NULL, 0, 'receiver', "
                    + "'13800000001', 'address', 'fee-null-request', REPEAT('b', 64), '" + HISTORICAL_TIME + "')");

            Flyway flyway = database.flyway(LATEST_VERSION, false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);

            assertThat(database.rows("SELECT id, delivery_fee, DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') "
                    + "FROM orders ORDER BY id"))
                    .containsExactly(row("90", "8.75", HISTORICAL_TIME), row("91", "0.00", HISTORICAL_TIME));
            assertThat(database.column("orders", "delivery_fee", "is_nullable")).isEqualTo("NO");
            assertThat(database.column("orders", "delivery_fee", "column_default")).isEqualTo("0.00");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @ParameterizedTest(name = "cart preflight rejects {0} before changing any header")
    @ValueSource(strings = {"multiple merchants", "orphan user", "nonpositive merchant"})
    void rejectsInvalidCartsBeforeAnyHeaderBackfill(String invalidCart) throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.baselinedFlyway("20261009").migrate();
            insertUser(database, 1);
            insertUser(database, 2);
            insertUser(database, 3);
            database.execute("INSERT INTO cart_header (user_id, merchant_id) VALUES (1, 99), (2, 99)");
            insertCart(database, 1, 30, 10);
            if (invalidCart.equals("multiple merchants")) {
                insertCart(database, 2, 31, 10);
                insertCart(database, 2, 32, 20);
            } else if (invalidCart.equals("orphan user")) {
                insertCart(database, 99, 31, 10);
            } else {
                insertCart(database, 2, 31, 0);
            }
            Map<String, List<List<String>>> originalRows = database.businessRows();
            List<List<String>> originalColumns = database.columnDefinitions();
            String expectedConstraint = switch (invalidCart) {
                case "multiple merchants" -> "chk_cart_header_single_merchant";
                case "orphan user" -> "chk_cart_header_user_exists";
                default -> "chk_cart_header_positive_merchant";
            };

            assertThatThrownBy(() -> database.flyway(LATEST_VERSION, false, BASELINE_VERSION).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining(expectedConstraint);

            assertThat(database.businessRows()).isEqualTo(originalRows);
            assertThat(database.columnDefinitions()).isEqualTo(originalColumns);
            assertThat(database.scalar("SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version = '20261010' AND success = 1")).isEqualTo("0");
        }
    }

    @ParameterizedTest(name = "product preflight rejects {0} category before changing defaults")
    @ValueSource(strings = {"null", "orphan"})
    void rejectsInvalidProductCategoriesBeforeChangingRuntimeSchema(String invalidCategory) throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.baselinedFlyway("20261010").migrate();
            insertMerchant(database, 10, 7);
            // Importing old data with FK checks disabled can leave orphan category references.
            database.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                database.execute("INSERT INTO product (id, category_id, product_name, price, merchant_id) "
                        + "VALUES (30, " + (invalidCategory.equals("null") ? "NULL" : "999")
                        + ", 'invalid historical product', 1.00, 10)");
            } finally {
                database.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            Map<String, List<List<String>>> originalRows = database.businessRows();
            List<List<String>> originalColumns = database.columnDefinitions();
            String originalVersionDefault = database.column("merchant", "version", "column_default");

            assertThatThrownBy(() -> database.flyway(LATEST_VERSION, false, BASELINE_VERSION).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining(invalidCategory.equals("null")
                            ? "chk_product_category_not_null" : "chk_product_category_exists");

            assertThat(database.businessRows()).isEqualTo(originalRows);
            assertThat(database.columnDefinitions()).isEqualTo(originalColumns);
            assertThat(database.column("merchant", "version", "column_default")).isEqualTo(originalVersionDefault);
            assertThat(database.column("product", "category_id", "is_nullable")).isEqualTo("YES");
            assertThat(database.scalar("SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version = '20261011' AND success = 1")).isEqualTo("0");
        }
    }

    @Test
    void flywayInitializesAnEmptyDatabaseAndDoesNothingOnTheSecondRun() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            assertThat(database.rows("SHOW TABLES")).isEmpty();
            Flyway flyway = database.flyway(LATEST_VERSION, true, "1");

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(19);

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(LATEST_VERSION);
            assertThat(database.rows("SHOW TABLES").stream().map(values -> values.get(0)))
                    .containsExactlyInAnyOrderElementsOf(withHistoryTable());
            assertLatestColumnDefinitions(database);
            Map<String, List<List<String>>> migratedRows = database.businessRows();
            List<List<String>> migratedColumns = database.columnDefinitions();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(database.businessRows()).isEqualTo(migratedRows);
            assertThat(database.columnDefinitions()).isEqualTo(migratedColumns);
        }
    }

    @Test
    void deploymentSchemaCanRunAllVersionedMigrationsAndDemoDataTwice() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.executeDeploymentScript(Path.of("deploy", "schema.sql"));
            Flyway flyway = database.flyway(LATEST_VERSION, true, "1");

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(26);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(LATEST_VERSION);
            assertThat(database.scalar("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'account_transaction'"))
                    .as("deployment schema must finish with only the current income ledger")
                    .isEqualTo("0");
            assertLatestColumnDefinitions(database);
            database.executeDeploymentScript(Path.of("deploy", "demo-data.sql"));
            assertDemoRows(database);
            database.executeDeploymentScript(Path.of("deploy", "demo-data.sql"));
            assertDemoRows(database);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void usernameMigrationMakesMerchantAndRiderLoginCaseAndAccentSensitive() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            insertMerchant(database, 10, 7);
            insertRider(database, 41);
            database.execute("UPDATE merchant SET username = 'Cafe' WHERE id = 10");
            database.execute("UPDATE rider SET name = 'Cafe' WHERE id = 41");
            database.baselinedFlyway("20261011").migrate();
            for (String[] loginColumn : List.of(new String[]{"merchant", "username"},
                    new String[]{"rider", "name"})) {
                assertThat(database.column(loginColumn[0], loginColumn[1], "collation_name"))
                        .isEqualTo("utf8mb4_0900_ai_ci");
                assertThat(database.scalar("SELECT COUNT(*) FROM " + loginColumn[0]
                        + " WHERE " + loginColumn[1] + " = 'cafe'"))
                        .as("historical %s login ignores case", loginColumn[0]).isEqualTo("1");
            }

            assertThat(database.flyway("20261012", false, BASELINE_VERSION)
                    .migrate().migrationsExecuted).isEqualTo(1);

            for (String[] loginColumn : List.of(new String[]{"merchant", "username"},
                    new String[]{"rider", "name"})) {
                assertThat(database.column(loginColumn[0], loginColumn[1], "collation_name"))
                        .isEqualTo("utf8mb4_0900_as_cs");
                assertThat(database.scalar("SELECT COUNT(*) FROM " + loginColumn[0]
                        + " WHERE " + loginColumn[1] + " = 'Cafe'"))
                        .as("stored %s login still resolves", loginColumn[0]).isEqualTo("1");
                for (String variant : List.of("cafe", "Caf\u00e9")) {
                    assertThat(database.scalar("SELECT COUNT(*) FROM " + loginColumn[0]
                            + " WHERE " + loginColumn[1] + " = '" + variant + "'"))
                            .as("%s login must distinguish %s", loginColumn[0], variant).isEqualTo("0");
                }
            }
        }
    }

    @Test
    void version12UnicodeEquivalentLookupsAreRejectedAtCurrentDtoBoundary() throws Exception {
        try (TestDatabase database = TestDatabase.create();
             ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            database.loadLocalSchema();
            insertUser(database, 1);
            insertMerchant(database, 10, 7);
            insertRider(database, 41);
            database.baselinedFlyway("20261012").migrate();
            Validator validator = validatorFactory.getValidator();

            for (String[] spellings : List.of(new String[]{"Cafe", "Cafe\u200b"},
                    new String[]{"Caf\u00e9", "Cafe\u0301"})) {
                MerchantLoginDTO merchantLogin = new MerchantLoginDTO();
                merchantLogin.setUsername(spellings[1]);
                merchantLogin.setPassword("Password1");
                RiderLoginDTO riderLogin = new RiderLoginDTO();
                riderLogin.setName(spellings[1]);
                riderLogin.setPassword("Password1");
                LoginDTO userLogin = new LoginDTO();
                userLogin.setUsername(spellings[1]);
                userLogin.setPassword("Password1");
                assertThat(validator.validate(merchantLogin))
                        .extracting(violation -> violation.getPropertyPath().toString()).contains("username");
                assertThat(validator.validate(riderLogin))
                        .extracting(violation -> violation.getPropertyPath().toString()).contains("name");
                assertThat(validator.validate(userLogin))
                        .extracting(violation -> violation.getPropertyPath().toString()).contains("username");

                // as_cs distinguishes case and accents, but UCA equivalence is not byte equality.
                for (String[] loginColumn : List.of(new String[]{"user", "username"},
                        new String[]{"merchant", "username"},
                        new String[]{"rider", "name"})) {
                    assertThat(database.column(loginColumn[0], loginColumn[1], "collation_name"))
                            .isEqualTo("utf8mb4_0900_as_cs");
                    database.execute("UPDATE `" + loginColumn[0] + "` SET " + loginColumn[1]
                            + " = '" + spellings[0] + "'");
                    assertThat(database.scalar("SELECT COUNT(*) FROM `" + loginColumn[0] + "`"
                            + " WHERE " + loginColumn[1] + " = '" + spellings[1] + "'"))
                            .as("%s UCA-equivalent spelling still resolves the same login", loginColumn[0])
                            .isEqualTo("1");
                }
            }
        }
    }

    @Test
    void binaryLoginMigrationPreservesAllThreeAccountsAndUsesExactLookupAndUniqueness() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            insertUser(database, 1);
            insertMerchant(database, 10, 7);
            insertRider(database, 41);
            List<String[]> loginColumns = List.of(new String[]{"user", "username"},
                    new String[]{"merchant", "username"}, new String[]{"rider", "name"});
            String originalName = "Caf\u00e9";
            List<String> variants = List.of("caf\u00e9", "Cafe", "Cafe\u0301", "Caf\u00e9\u200b", "Caf\u00e9 ");
            for (String[] loginColumn : loginColumns) {
                database.execute("UPDATE `" + loginColumn[0] + "` SET `" + loginColumn[1]
                        + "` = '" + originalName + "'");
            }
            database.baselinedFlyway("20261012").migrate();
            Map<String, List<List<String>>> existingRows = database.businessRows();
            for (String[] loginColumn : loginColumns) {
                assertThat(database.column(loginColumn[0], loginColumn[1], "collation_name"))
                        .isEqualTo("utf8mb4_0900_as_cs");
            }

            Flyway flyway = database.flyway("20261013", false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20261013");
            assertThat(database.businessRows()).isEqualTo(existingRows);
            for (String[] loginColumn : loginColumns) {
                assertThat(database.column(loginColumn[0], loginColumn[1], "collation_name"))
                        .isEqualTo("utf8mb4_0900_bin");
            }

            int phoneSuffix = 100;
            for (String[] loginColumn : loginColumns) {
                String lookupPrefix = "SELECT COUNT(*) FROM `" + loginColumn[0]
                        + "` WHERE `" + loginColumn[1] + "` = '";
                assertThat(database.scalar(lookupPrefix + originalName + "'"))
                        .as("%s original login remains unchanged", loginColumn[0]).isEqualTo("1");
                for (String variant : variants) {
                    assertThat(database.scalar(lookupPrefix + variant + "'"))
                            .as("%s must distinguish variant %s", loginColumn[0], variant).isEqualTo("0");
                    insertLoginAccount(database, loginColumn[0], variant,
                            String.format("13800000%03d", phoneSuffix++));
                    assertThat(database.scalar(lookupPrefix + variant + "'"))
                            .as("%s variant has its own exact login", loginColumn[0]).isEqualTo("1");
                }
                assertThat(database.scalar("SELECT COUNT(*) FROM `" + loginColumn[0] + "`"))
                        .isEqualTo(String.valueOf(variants.size() + 1));
                String distinctPhone = String.format("13800000%03d", phoneSuffix++);
                assertThatThrownBy(() -> insertLoginAccount(database, loginColumn[0], originalName, distinctPhone))
                        .as("%s exact duplicate name is still rejected", loginColumn[0])
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            }
            Map<String, List<List<String>>> newRows = database.businessRows();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(database.businessRows()).isEqualTo(newRows);
        }
    }

    @Test
    void version14RestoresAsCsWithoutChangingExistingAccountValuesAndDoesNothingOnRerun() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            insertUser(database, 1);
            insertMerchant(database, 10, 7);
            insertRider(database, 41);
            database.baselinedFlyway("20261013").migrate();
            List<String[]> loginColumns = List.of(new String[]{"user", "username"},
                    new String[]{"merchant", "username"}, new String[]{"rider", "name"});
            int phoneSuffix = 100;
            for (String[] loginColumn : loginColumns) {
                assertThat(database.column(loginColumn[0], loginColumn[1], "collation_name"))
                        .isEqualTo("utf8mb4_0900_bin");
                // Historical Unicode names are preserved, not silently renamed to fit the new DTO rule.
                database.execute("UPDATE `" + loginColumn[0] + "` SET `" + loginColumn[1]
                        + "` = 'Caf\u00e9'");
                insertLoginAccount(database, loginColumn[0], "Ascii_Name-9",
                        String.format("13800000%03d", phoneSuffix++));
            }
            Map<String, List<List<String>>> originalRows = database.businessRows();
            List<List<String>> originalColumns = database.columnDefinitions();
            List<List<String>> unrelatedCollations = database.rows("SELECT table_name, column_name, collation_name "
                    + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                    + "AND table_name <> 'flyway_schema_history' "
                    + "AND NOT ((table_name IN ('user', 'merchant') AND column_name = 'username') "
                    + "OR (table_name = 'rider' AND column_name = 'name')) ORDER BY table_name, ordinal_position");

            Flyway flyway = database.flyway(VERSION_14, false, BASELINE_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(VERSION_14);
            assertRuntimeColumnDefinitions(database);
            assertThat(database.businessRows()).as("all values, passwords and account IDs survive V14")
                    .isEqualTo(originalRows);
            assertThat(database.columnDefinitions()).isEqualTo(originalColumns);
            assertThat(database.rows("SELECT table_name, column_name, collation_name "
                    + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                    + "AND table_name <> 'flyway_schema_history' "
                    + "AND NOT ((table_name IN ('user', 'merchant') AND column_name = 'username') "
                    + "OR (table_name = 'rider' AND column_name = 'name')) ORDER BY table_name, ordinal_position"))
                    .as("V14 changes only the three login collations").isEqualTo(unrelatedCollations);
            for (String[] loginColumn : loginColumns) {
                assertThat(database.scalar("SELECT COUNT(*) FROM `" + loginColumn[0]
                        + "` WHERE `" + loginColumn[1] + "` = 'Ascii_Name-9'"))
                        .as("legal ASCII login remains resolvable for %s", loginColumn[0]).isEqualTo("1");
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(database.businessRows()).isEqualTo(originalRows);
            assertThat(database.columnDefinitions()).isEqualTo(originalColumns);
            assertRuntimeColumnDefinitions(database);
        }
    }

    @ParameterizedTest(name = "V14 rejects {1} collision in {0} before changing any login collation")
    @CsvSource({"user,Kelvin", "merchant,Kelvin", "rider,Kelvin",
            "user,zero-width", "merchant,zero-width", "rider,zero-width"})
    void version14PreflightsAllThreeRolesBeforeChangingAnyLoginColumn(String table, String equivalence)
            throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            insertUser(database, 1);
            insertMerchant(database, 10, 7);
            insertRider(database, 41);
            database.baselinedFlyway("20261013").migrate();
            String column = table.equals("rider") ? "name" : "username";
            String originalName = equivalence.equals("Kelvin") ? "KAccount" : "Account";
            String equivalentName = equivalence.equals("Kelvin") ? "\u212aAccount" : "Account\u200b";
            database.execute("UPDATE `" + table + "` SET `" + column + "` = '" + originalName + "'");
            insertLoginAccount(database, table, equivalentName, "13800000999");
            assertThat(database.scalar("SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` = '"
                    + originalName + "' COLLATE utf8mb4_0900_as_cs"))
                    .as("the existing binary-distinct rows collide under the proposed as_cs target")
                    .isEqualTo("2");
            Map<String, List<List<String>>> originalRows = database.businessRows();
            List<List<String>> originalColumns = database.columnDefinitions();

            assertThatThrownBy(() -> database.flyway(VERSION_14, false, BASELINE_VERSION).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("chk_" + table + "_login_name_as_cs_unique");

            assertThat(database.businessRows()).isEqualTo(originalRows);
            assertThat(database.columnDefinitions()).isEqualTo(originalColumns);
            for (String[] loginColumn : List.of(new String[]{"user", "username"},
                    new String[]{"merchant", "username"}, new String[]{"rider", "name"})) {
                assertThat(database.column(loginColumn[0], loginColumn[1], "collation_name"))
                        .as("preflight failure must leave %s.%s untouched", loginColumn[0], loginColumn[1])
                        .isEqualTo("utf8mb4_0900_bin");
            }
            assertThat(database.scalar("SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version = '20261014' AND success = 1")).isEqualTo("0");
        }
    }

    @Test
    void version17BackfillsMerchantSeenAndCreatesReconnectIndexWithoutChangingOrderUpdateTime()
            throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            database.baselinedFlyway(VERSION_14).migrate();
            insertUser(database, 1);
            insertMerchant(database, 10, 7);
            database.execute("""
                    INSERT INTO orders (
                        id, order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                        total_amount, status, receiver_name, receiver_phone, receiver_address,
                        create_time, update_time
                    ) VALUES
                        (101, 'SEEN-MIG-0', 1, 'seen-mig-0', REPEAT('a', 64), 10, 'merchant',
                         10.00, 0, 'receiver', '13800000001', 'address',
                         '2024-01-02 03:04:05', '2024-01-02 03:04:05'),
                        (102, 'SEEN-MIG-1', 1, 'seen-mig-1', REPEAT('b', 64), 10, 'merchant',
                         10.00, 1, 'receiver', '13800000001', 'address',
                         '2024-01-02 03:04:05', '2024-01-02 03:04:05'),
                        (103, 'SEEN-MIG-2', 1, 'seen-mig-2', REPEAT('c', 64), 10, 'merchant',
                         10.00, 2, 'receiver', '13800000001', 'address',
                         '2024-01-02 03:04:05', '2024-01-02 03:04:05'),
                        (104, 'SEEN-MIG-5', 1, 'seen-mig-5', REPEAT('d', 64), 10, 'merchant',
                         10.00, 5, 'receiver', '13800000001', 'address',
                         '2024-01-02 03:04:05', '2024-01-02 03:04:05')
                    """);

            Flyway flyway = database.flyway(LATEST_VERSION, false, BASELINE_VERSION);
            // The historical fixture includes PAYING. Give it its durable record before the recovery preflight.
            assertThat(database.flyway("20261030.2", false, BASELINE_VERSION).migrate().migrationsExecuted).isEqualTo(8);
            database.execute("INSERT INTO order_stripe_payments (order_id, amount_minor, currency, idempotency_key) "
                    + "VALUES (102, 1000, 'cny', 'seen-payment-102')");
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);

            assertLatestColumnDefinitions(database);
            assertThat(database.rows("SELECT id, merchant_seen, "
                    + "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') FROM orders ORDER BY id"))
                    .containsExactly(
                            row("101", "0", HISTORICAL_TIME),
                            row("102", "0", HISTORICAL_TIME),
                            row("103", "0", HISTORICAL_TIME),
                            row("104", "1", HISTORICAL_TIME));
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void cancelSourceMigrationAddsNullableColumnAndLeavesHistoricalCancellationsUnknown() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.loadLocalSchema();
            insertUser(database, 1);
            insertMerchant(database, 10, 7);
            database.execute("""
                    INSERT INTO orders (
                        id, order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                        total_amount, status, receiver_name, receiver_phone, receiver_address,
                        create_time, update_time
                    ) VALUES
                        (101, 'CANCEL-SOURCE-4', 1, 'cancel-source-4', REPEAT('a', 64), 10, 'merchant',
                         10.00, 4, 'receiver', '13800000001', 'address',
                         '2024-01-02 03:04:05', '2024-01-02 03:04:05')
                    """);

            Flyway flyway = database.baselinedFlyway(LATEST_VERSION);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(18);

            assertThat(database.column("orders", "cancel_source", "is_nullable")).isEqualTo("YES");
            assertThat(database.column("orders", "cancel_source", "column_default")).isNull();
            assertThat(database.scalar("SELECT cancel_source FROM orders WHERE id = 101")).isNull();
            database.execute("UPDATE orders SET cancel_source = 2 WHERE id = 101");
            assertThat(database.scalar("SELECT cancel_source FROM orders WHERE id = 101")).isEqualTo("2");
        }
    }

    @Test
    void legacyMysqlTestFixtureBackfillsSeenAndPreservesAcknowledgementsOnRepeat() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.execute("""
                    CREATE TABLE orders (
                        id BIGINT PRIMARY KEY,
                        status INT NOT NULL,
                        update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP
                    )
                    """);
            database.execute("""
                    INSERT INTO orders (id, status, update_time) VALUES
                        (101, 0, '2024-01-02 03:04:05'),
                        (102, 1, '2024-01-02 03:04:05'),
                        (103, 2, '2024-01-02 03:04:05'),
                        (104, 5, '2024-01-02 03:04:05')
                    """);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                    database.schemaUrl, database.user, database.password));

            OrderMerchantSeenSchemaTestFixture.ensureMerchantSeenColumn(jdbcTemplate);

            assertThat(database.rows("SELECT id, merchant_seen, "
                    + "DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') FROM orders ORDER BY id"))
                    .containsExactly(
                            row("101", "0", HISTORICAL_TIME),
                            row("102", "0", HISTORICAL_TIME),
                            row("103", "0", HISTORICAL_TIME),
                            row("104", "1", HISTORICAL_TIME));
            assertThat(database.column("orders", "merchant_seen", "is_nullable")).isEqualTo("NO");
            assertThat(database.column("orders", "merchant_seen", "column_default")).isEqualTo("0");
            database.execute("UPDATE orders SET merchant_seen = 1, update_time = update_time WHERE id = 103");
            List<List<String>> acknowledgedRows = database.rows("SELECT * FROM orders ORDER BY id");

            OrderMerchantSeenSchemaTestFixture.ensureMerchantSeenColumn(jdbcTemplate);

            assertThat(database.rows("SELECT * FROM orders ORDER BY id")).isEqualTo(acknowledgedRows);
            database.execute("INSERT INTO orders (id, status) VALUES (105, 2)");
            assertThat(database.scalar("SELECT merchant_seen FROM orders WHERE id = 105")).isEqualTo("0");
        }
    }

    @Test
    void permittedAsciiAlphabetHasDistinctNonIgnorableFixedWidthAsCsWeights() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
            StringJoiner permittedSql = new StringJoiner(" UNION ALL ");
            for (char character : alphabet.toCharArray()) {
                permittedSql.add("SELECT _utf8mb4'" + character
                        + "' COLLATE utf8mb4_0900_as_cs AS login_character");
            }
            String characterRows = "(" + permittedSql + ")";
            assertThat(database.scalar("SELECT COUNT(DISTINCT login_character) FROM " + characterRows + " permitted"))
                    .isEqualTo("64");
            Map<String, String> weights = new HashMap<>();
            for (List<String> character : database.rows("SELECT login_character, HEX(WEIGHT_STRING(login_character)) "
                    + "FROM " + characterRows + " permitted")) {
                String weight = character.get(1);
                // A single ASCII character has one two-byte weight at each of the three UCA levels.
                // Zero-level separators are explicit, and no permitted character is ignorable.
                assertThat(weight).as("fixed-width as_cs weight for %s", character.get(0)).hasSize(20);
                assertThat(weight.substring(4, 8)).isEqualTo("0000");
                assertThat(weight.substring(12, 16)).isEqualTo("0000");
                for (int start : List.of(0, 8, 16)) {
                    assertThat(weight.substring(start, start + 4))
                            .as("non-ignorable level weight for %s", character.get(0)).isNotEqualTo("0000");
                }
                weights.put(character.get(0), weight);
            }
            assertThat(weights).hasSize(64);
            assertThat(new HashSet<>(weights.values())).hasSize(64);
            assertThat(database.scalar("SELECT PAD_ATTRIBUTE FROM information_schema.collations "
                    + "WHERE COLLATION_NAME = 'utf8mb4_0900_as_cs'")).isEqualTo("NO PAD");

            Set<String> pairWeights = new HashSet<>();
            List<List<String>> pairs = database.rows("SELECT CONCAT(a.login_character, b.login_character), "
                    + "HEX(WEIGHT_STRING(CONCAT(a.login_character, b.login_character))) FROM "
                    + characterRows + " a CROSS JOIN " + characterRows + " b");
            assertThat(pairs).hasSize(64 * 64);
            for (List<String> pair : pairs) {
                String first = weights.get(pair.get(0).substring(0, 1));
                String second = weights.get(pair.get(0).substring(1, 2));
                String composedWeight = first.substring(0, 4) + second.substring(0, 4) + "0000"
                        + first.substring(8, 12) + second.substring(8, 12) + "0000"
                        + first.substring(16, 20) + second.substring(16, 20);
                assertThat(pair.get(1)).as("no contraction or ignored character in %s", pair.get(0))
                        .isEqualTo(composedWeight);
                pairWeights.add(pair.get(1));
            }
            assertThat(pairWeights).hasSize(64 * 64);
            for (String spelling : List.of(alphabet, new StringBuilder(alphabet).reverse().toString(),
                    "A_a-0".repeat(10))) {
                StringBuilder primary = new StringBuilder();
                StringBuilder secondary = new StringBuilder();
                StringBuilder tertiary = new StringBuilder();
                for (char character : spelling.toCharArray()) {
                    String weight = weights.get(String.valueOf(character));
                    primary.append(weight, 0, 4);
                    secondary.append(weight, 8, 12);
                    tertiary.append(weight, 16, 20);
                }
                assertThat(database.scalar("SELECT HEX(WEIGHT_STRING(_utf8mb4'" + spelling
                        + "' COLLATE utf8mb4_0900_as_cs))"))
                        .isEqualTo(primary + "0000" + secondary + "0000" + tertiary);
            }
        }
    }

    private static void assertDemoRows(TestDatabase database) throws SQLException {
        assertThat(database.scalar("SELECT COUNT(*) FROM `user` WHERE username = 'demo_user'")).isEqualTo("1");
        assertThat(database.scalar("SELECT COUNT(*) FROM merchant WHERE username = 'demo_merchant'")).isEqualTo("1");
        assertThat(database.scalar("SELECT COUNT(*) FROM rider WHERE name = 'demo_rider'")).isEqualTo("1");
        assertThat(database.scalar("SELECT COUNT(*) FROM orders")).isEqualTo("4");
        assertThat(database.scalar("SELECT COUNT(*) FROM delivery_task")).isEqualTo("3");
        assertThat(database.rows("SELECT delivery_reward FROM delivery_task ORDER BY id"))
                .allSatisfy(values -> assertThat(new BigDecimal(values.get(0))).isEqualByComparingTo("5.00"));
        assertThat(new BigDecimal(database.scalar(
                "SELECT longitude FROM merchant WHERE username = 'demo_merchant'")))
                .isEqualByComparingTo("121.4737");
        assertThat(new BigDecimal(database.scalar(
                "SELECT latitude FROM merchant WHERE username = 'demo_merchant'")))
                .isEqualByComparingTo("31.2304");
        assertThat(database.rows("SELECT pickup_longitude, pickup_latitude FROM delivery_task ORDER BY id"))
                .allSatisfy(values -> {
                    assertThat(new BigDecimal(values.get(0))).isEqualByComparingTo("121.4737");
                    assertThat(new BigDecimal(values.get(1))).isEqualByComparingTo("31.2304");
                });
        assertThat(database.scalar("SELECT COUNT(*) FROM delivery_task d LEFT JOIN orders o ON o.id = d.order_id "
                + "WHERE o.id IS NULL")).isEqualTo("0");
        assertThat(database.rows("SELECT u.username, m.username FROM cart_header h "
                + "JOIN `user` u ON u.id = h.user_id JOIN merchant m ON m.id = h.merchant_id "
                + "WHERE u.username = 'demo_user'"))
                .containsExactly(row("demo_user", "demo_merchant"));
        assertThat(database.scalar("SELECT COUNT(*) FROM cart c JOIN `user` u ON u.id = c.user_id "
                + "WHERE u.username = 'demo_user'")).isEqualTo("1");
        assertThat(database.scalar("SELECT COUNT(*) FROM cart c JOIN `user` u ON u.id = c.user_id "
                + "LEFT JOIN cart_header h ON h.user_id = c.user_id WHERE u.username = 'demo_user' "
                + "AND NOT (h.merchant_id <=> c.merchant_id)")).isEqualTo("0");
    }

    private static void assertRuntimeColumnDefinitions(TestDatabase database) throws SQLException {
        assertThat(database.column("delivery_task", "id", "extra")).contains("auto_increment");
        assertThat(database.column("rider", "id", "extra")).contains("auto_increment");
        assertThat(database.column("delivery_task", "delivery_reward", "is_nullable")).isEqualTo("NO");
        assertThat(database.column("merchant", "version", "column_default")).isEqualTo("0");
        assertThat(database.column("product", "category_id", "is_nullable")).isEqualTo("NO");
        assertThat(database.column("product", "stock", "column_default")).isEqualTo("0");
        assertThat(database.column("cache_invalidation_task", "status", "column_comment"))
                .contains("PENDING", "SUCCESS", "FAILED");
        assertThat(database.column("user", "username", "collation_name"))
                .isEqualTo("utf8mb4_0900_as_cs");
        assertThat(database.column("merchant", "username", "collation_name"))
                .isEqualTo("utf8mb4_0900_as_cs");
        assertThat(database.column("rider", "name", "collation_name"))
                .isEqualTo("utf8mb4_0900_as_cs");
    }

    private static void assertLatestColumnDefinitions(TestDatabase database) throws SQLException {
        assertRuntimeColumnDefinitions(database);
        assertAccountSettlementDefinitions(database);
        assertStripePaymentRecoveryDefinitions(database);
        assertStripeRefundRecoveryDefinitions(database);
        assertThat(database.column("orders", "merchant_seen", "is_nullable")).isEqualTo("NO");
        assertThat(database.column("orders", "merchant_seen", "column_default")).isEqualTo("0");
        assertThat(database.column("orders", "cancel_source", "is_nullable")).isEqualTo("YES");
        assertThat(database.column("orders", "cancel_source", "column_default")).isNull();
        assertThat(database.column("orders", "cancel_source", "column_comment"))
                .contains("1用户主动取消", "2商家拒单", "3系统超时取消", "4平台取消");
        assertThat(database.column("orders", "delivery_fee", "is_nullable")).isEqualTo("NO");
        assertThat(database.column("orders", "delivery_fee", "column_default")).isEqualTo("0.00");
        assertThat(database.column("orders", "delivery_fee", "column_comment")).contains("配送费");
        assertThat(database.column("product", "stock_version", "is_nullable")).isEqualTo("NO");
        assertThat(database.column("product", "stock_version", "column_default")).isEqualTo("0");
        assertThat(database.column("product", "stock_version", "column_comment"))
                .contains("库存基准版本");
        assertThat(database.column("order_item", "stock_version", "is_nullable")).isEqualTo("NO");
        assertThat(database.column("order_item", "stock_version", "column_default")).isEqualTo("0");
        assertThat(database.column("order_item", "stock_version", "column_comment"))
                .contains("预扣库存");
        assertThat(database.column("merchant", "longitude", "is_nullable")).isEqualTo("YES");
        assertThat(database.column("merchant", "latitude", "is_nullable")).isEqualTo("YES");
        assertThat(database.column("delivery_task", "pickup_longitude", "is_nullable")).isEqualTo("YES");
        assertThat(database.column("delivery_task", "pickup_latitude", "is_nullable")).isEqualTo("YES");
        assertThat(database.rows("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'orders' "
                + "AND index_name = 'idx_order_merchant_status_seen_create_time_id' ORDER BY seq_in_index"))
                .containsExactly(
                        row("merchant_id"), row("status"), row("merchant_seen"),
                        row("create_time"), row("id"));
    }

    private static void assertStripePaymentRecoveryDefinitions(TestDatabase database) throws SQLException {
        String table = "order_stripe_payments";
        assertThat(database.column(table, "reconciliation_state", "column_type")).isEqualTo("varchar(16)");
        assertThat(database.column(table, "reconciliation_state", "collation_name")).isEqualTo("ascii_bin");
        assertThat(database.column(table, "reconciliation_state", "is_nullable")).isEqualTo("NO");
        assertThat(database.column(table, "reconciliation_state", "column_default")).isEqualTo("PENDING");
        assertThat(database.column(table, "retry_count", "column_type")).matches("int(?:\\(\\d+\\))?");
        assertThat(database.column(table, "retry_count", "is_nullable")).isEqualTo("NO");
        assertThat(database.column(table, "retry_count", "column_default")).isEqualTo("0");
        assertThat(database.column(table, "next_retry_at", "column_type")).isEqualTo("datetime");
        assertThat(database.column(table, "next_retry_at", "is_nullable")).isEqualTo("YES");
        assertThat(database.column(table, "next_retry_at", "column_default"))
                .containsIgnoringCase("current_timestamp");

        assertThat(database.rows("""
                SELECT column_name, non_unique FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'order_stripe_payments'
                  AND index_name = 'idx_stripe_recovery_due'
                ORDER BY seq_in_index
                """)).containsExactly(row("reconciliation_state", "1"), row("next_retry_at", "1"), row("order_id", "1"));

        List<List<String>> recoveryChecks = database.rows("""
                SELECT tc.constraint_name, tc.enforced, cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
                WHERE tc.table_schema = DATABASE() AND tc.table_name = 'order_stripe_payments'
                  AND tc.constraint_type = 'CHECK'
                  AND tc.constraint_name IN ('chk_stripe_reconciliation_state', 'chk_stripe_retry_count')
                ORDER BY tc.constraint_name
                """);
        // MySQL can include charset introducers and escaped quotes in CHECK_CLAUSE.
        assertThat(recoveryChecks.stream().map(check -> row(check.get(0), check.get(1), check.get(2)
                        .replace("\\'", "'")
                        .replaceAll("(?i)_[a-z0-9]+(?=')|[\\s`()]+", "")
                        .replaceAll("(?i)^reconciliation_statein", "reconciliation_statein"))).toList())
                .containsExactly(
                        row("chk_stripe_reconciliation_state", "YES", "reconciliation_statein'PENDING','COMPLETE','REVIEW'"),
                        row("chk_stripe_retry_count", "YES", "retry_count>=0"));

        assertThat(database.rows("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'order_stripe_payments'
                  AND column_name IN ('idempotency_expires_at', 'lease_token', 'lease_until', 'last_error')
                """)).isEmpty();
    }

    private static void assertStripeRefundRecoveryDefinitions(TestDatabase database) throws SQLException {
        String table = "order_stripe_refunds";
        assertThat(database.column(table, "retry_count", "column_type")).matches("int(?:\\(\\d+\\))?");
        assertThat(database.column(table, "retry_count", "is_nullable")).isEqualTo("NO");
        assertThat(database.column(table, "retry_count", "column_default")).isEqualTo("0");
        assertThat(database.column(table, "next_retry_at", "column_type")).isEqualTo("datetime");
        assertThat(database.column(table, "next_retry_at", "is_nullable")).isEqualTo("YES");
        assertThat(database.column(table, "next_retry_at", "column_default")).isNull();
    }

    private static void assertAccountSettlementDefinitions(TestDatabase database) throws SQLException {
        for (String table : List.of("merchant", "rider")) {
            assertThat(database.column(table, "balance", "column_type")).isEqualToIgnoringCase("decimal(12,2)");
            assertThat(database.column(table, "balance", "is_nullable")).isEqualTo("NO");
            assertThat(database.column(table, "balance", "column_default")).isEqualTo("0.00");
            assertThat(database.column(table, "total_income", "column_type")).isEqualToIgnoringCase("decimal(12,2)");
            assertThat(database.column(table, "total_income", "is_nullable")).isEqualTo("NO");
            assertThat(database.column(table, "total_income", "column_default")).isEqualTo("0.00");
        }
        assertThat(database.column("income_record", "beneficiary_type", "column_type"))
                .isEqualToIgnoringCase("tinyint");
        assertThat(database.column("income_record", "type", "column_type"))
                .isEqualToIgnoringCase("tinyint");
        assertThat(database.column("income_record", "amount", "column_type"))
                .isEqualToIgnoringCase("decimal(12,2)");
        assertThat(database.column("income_record", "create_time", "is_nullable")).isEqualTo("NO");
        assertThat(database.column("income_record", "create_time", "column_default"))
                .containsIgnoringCase("current_timestamp");
        assertThat(database.rows("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'income_record' "
                + "AND index_name = 'uk_income_record_beneficiary_order_type' ORDER BY seq_in_index"))
                .containsExactly(row("beneficiary_type"), row("beneficiary_id"), row("order_id"), row("type"));
        assertThat(database.rows("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'income_record' "
                + "AND index_name = 'uk_income_record_order_type' ORDER BY seq_in_index"))
                .containsExactly(row("order_id"), row("type"));
        assertThat(database.rows("SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema = DATABASE() AND table_name = 'income_record' "
                + "AND constraint_type = 'CHECK' ORDER BY constraint_name"))
                .containsExactly(row("chk_income_record_event_identity"),
                        row("chk_income_record_nonnegative_amount"));
        assertThat(database.scalar("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = DATABASE() AND table_name = 'income_record' "
                + "AND constraint_name = 'fk_income_record_order' AND constraint_type = 'FOREIGN KEY'"))
                .isEqualTo("1");
    }

    private static void insertLoginAccount(TestDatabase database, String table, String loginName, String phone)
            throws SQLException {
        String values = "'" + loginName + "', '" + phone + "', 'hash'";
        switch (table) {
            case "user" -> database.execute("INSERT INTO `user` (username, phone, password, status) VALUES ("
                    + values + ", 1)");
            case "merchant" -> database.execute("INSERT INTO merchant (username, phone, password, merchant_name) VALUES ("
                    + values + ", 'separate login')");
            case "rider" -> database.execute("INSERT INTO rider (name, phone, password, status, create_time, is_delete) VALUES ("
                    + values + ", 1, '" + HISTORICAL_TIME + "', 0)");
            default -> throw new IllegalArgumentException("Unsupported login table: " + table);
        }
    }

    private static void insertUser(TestDatabase database, long id) throws SQLException {
        database.execute("INSERT INTO `user` (id, phone, password, username, create_time, update_time) VALUES ("
                + id + ", '1380000000" + id + "', 'hash', 'historical_user_" + id + "', '"
                + HISTORICAL_TIME + "', '" + HISTORICAL_TIME + "')");
    }

    private static void insertMerchant(TestDatabase database, long id, int version) throws SQLException {
        database.execute("INSERT INTO merchant (id, username, phone, password, merchant_name, version, create_time) "
                + "VALUES (" + id + ", 'historical_merchant_" + id + "', '13800000010', 'hash', "
                + "'historical merchant', " + version + ", '" + HISTORICAL_TIME + "')");
    }

    private static void prepareIncomeEncodingMigration(TestDatabase database) throws SQLException {
        database.loadLocalSchema();
        insertUser(database, 1);
        insertMerchant(database, 10, 7);
        // Merchant and rider IDs are independent namespaces and may legitimately overlap.
        insertRider(database, 10);
        database.baselinedFlyway("20261030").migrate();
    }

    private static void insertSettlementOrder(TestDatabase database, long id) throws SQLException {
        database.execute("INSERT INTO orders (id, order_no, user_id, merchant_id, merchant_name, total_amount, "
                + "status, receiver_name, receiver_phone, receiver_address, request_id, request_hash) "
                + "VALUES (" + id + ", 'income-order-" + id + "', 1, 10, 'merchant', 17.50, 0, "
                + "'receiver', '13800000001', 'address', 'income-request-" + id + "', REPEAT('a', 64))");
    }

    private static void createIncomeRecordTable(TestDatabase database) throws SQLException {
        database.execute("CREATE TABLE income_record ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "beneficiary_type TINYINT NOT NULL, beneficiary_id BIGINT NOT NULL, "
                + "order_id BIGINT NOT NULL, type TINYINT NOT NULL, amount DECIMAL(12,2) NOT NULL, "
                + "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_income_record_order_type (order_id, type))");
    }

    private static void insertDefaultedMerchant(TestDatabase database) throws SQLException {
        database.execute("INSERT INTO merchant (username, phone, password, merchant_name) "
                + "VALUES ('defaulted_merchant', '13800000100', 'hash', 'defaulted merchant')");
    }

    private static void insertCart(TestDatabase database, long userId, long productId, long merchantId)
            throws SQLException {
        database.execute("INSERT INTO cart (user_id, product_id, product_name, quantity, price, merchant_id, "
                + "product_image, create_time, update_time, version) VALUES (" + userId + ", " + productId
                + ", 'historical cart product', 3, 12.50, " + merchantId + ", '/historical.png', '"
                + HISTORICAL_TIME + "', '" + HISTORICAL_TIME + "', 2)");
    }

    private static void insertRider(TestDatabase database, long id) throws SQLException {
        database.execute("INSERT INTO rider (id, name, phone, password, status, create_time, update_time, is_delete) "
                + "VALUES (" + id + ", 'historical rider', '13800000041', 'hash', 1, '"
                + HISTORICAL_TIME + "', '" + HISTORICAL_TIME + "', 0)");
    }

    private static void insertDeliveryTask(TestDatabase database, long id, Long riderId) throws SQLException {
        database.execute("INSERT INTO delivery_task (id, order_id, rider_id, merchant_name, status, create_time, "
                + "accepted_time, update_time, receiver_address, receiver_phone, merchant_address, "
                + "merchant_phone, receiver_name) VALUES (" + id + ", " + (1000 + id) + ", " + riderId
                + ", 'historical merchant', 1, '" + HISTORICAL_TIME + "', '" + HISTORICAL_TIME + "', '"
                + HISTORICAL_TIME + "', 'receiver address', '13800000001', 'merchant address', "
                + "'13800000010', 'receiver')");
    }

    private static String baselineSelectColumns(String table) {
        return switch (table) {
            case "merchant" -> "id, username, phone, password, address, status, create_time, "
                    + "merchant_name, picture, merchant_description, opening_time, closing_time, version";
            case "rider" -> "id, name, phone, password, status, create_time, update_time, is_delete";
            case "product" -> "id, category_id, product_name, image_url, price, stock, merchant_id, "
                    + "is_deleted, status, description, create_time, update_time, version, active_name_guard";
            case "orders" -> "id, order_no, user_id, merchant_id, merchant_name, total_amount, status, "
                    + "receiver_name, receiver_phone, receiver_address, remark, create_time, update_time, "
                    + "finish_time, original_amount, discount_amount, pay_time, request_Id, request_hash, "
                    + "timeout_cancel_available_at";
            case "order_item" -> "id, order_id, product_id, product_name, product_price, quantity, "
                    + "subtotal, product_picture";
            default -> "*";
        };
    }

    private static List<String> withHistoryTable() {
        List<String> tables = new ArrayList<>(BUSINESS_TABLES);
        tables.add("order_stripe_payments");
        tables.add("stripe_payment_events");
        tables.add("order_stripe_refunds");
        tables.add("flyway_schema_history");
        return tables;
    }

    private static List<String> row(String... values) {
        // List.of rejects null, which is meaningful when checking a cart without a merchant.
        return java.util.Arrays.asList(values);
    }

    private static final class TestDatabase implements AutoCloseable {
        private final String schema;
        private final String serverUrl;
        private final String schemaUrl;
        private final String user;
        private final String password;
        private final Connection connection;

        private TestDatabase(String schema, String serverUrl, String schemaUrl, String user,
                             String password, Connection connection) {
            this.schema = schema;
            this.serverUrl = serverUrl;
            this.schemaUrl = schemaUrl;
            this.user = user;
            this.password = password;
            this.connection = connection;
        }

        private static TestDatabase create() throws SQLException {
            String schema = "takeout_migration_test_" + UUID.randomUUID().toString().replace("-", "");
            String host = System.getProperty("takeout.migration-test.mysql.host", "localhost");
            String port = System.getProperty("takeout.migration-test.mysql.port", "3306");
            if (!host.matches("[a-zA-Z0-9.\\-\\[\\]:]+") || !port.matches("[0-9]{1,5}")) {
                throw new IllegalArgumentException("Migration test host/port must not contain a JDBC path or options");
            }
            String user = System.getProperty("takeout.migration-test.mysql.user", "root");
            String password = System.getProperty("takeout.migration-test.mysql.password", "root");
            String baseUrl = "jdbc:mysql://" + host + ":" + port + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8&characterEncoding=UTF-8";
            String serverUrl = baseUrl + options;
            String schemaUrl = baseUrl + schema + options;
            try (Connection admin = DriverManager.getConnection(serverUrl, user, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            try {
                return new TestDatabase(schema, serverUrl, schemaUrl, user, password,
                        DriverManager.getConnection(schemaUrl, user, password));
            } catch (SQLException failure) {
                try {
                    dropSchema(serverUrl, user, password, schema);
                } catch (SQLException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private void loadLocalSchema() {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration-fixtures/local-schema-20261008.sql"), StandardCharsets.UTF_8));
        }

        private void executeDeploymentScript(Path path) throws IOException {
            String sql = Files.readString(path, StandardCharsets.UTF_8)
                    .replaceAll("(?ims)^CREATE\\s+DATABASE\\s+[^;]*;\\s*", "")
                    .replaceAll("(?im)^USE\\s+[^;]*;\\s*", "");
            // Prevent a future deployment-script change from switching out of our owned database.
            if (java.util.regex.Pattern.compile("(?im)^\\s*(?:USE\\b|(?:CREATE|ALTER|DROP)\\s+(?:DATABASE|SCHEMA)\\b)")
                    .matcher(sql).find()) {
                throw new IllegalArgumentException("Deployment script still contains database-level statements: " + path);
            }
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        }

        private Flyway baselinedFlyway(String target) {
            Flyway flyway = flyway(target, false, BASELINE_VERSION);
            flyway.baseline();
            return flyway;
        }

        private Flyway flyway(String target, boolean baselineOnMigrate, String baselineVersion) {
            return Flyway.configure()
                    .dataSource(schemaUrl, user, password)
                    .locations("classpath:db/migration")
                    .defaultSchema(schema)
                    .schemas(schema)
                    .createSchemas(false)
                    .baselineOnMigrate(baselineOnMigrate)
                    .baselineVersion(baselineVersion)
                    .target(target)
                    .cleanDisabled(true)
                    .load();
        }

        private void execute(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        private String scalar(String sql) throws SQLException {
            List<List<String>> values = rows(sql);
            assertThat(values).hasSize(1);
            assertThat(values.get(0)).hasSize(1);
            return values.get(0).get(0);
        }

        private List<List<String>> rows(String sql) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                List<List<String>> rows = new ArrayList<>();
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<String> row = new ArrayList<>();
                    for (int column = 1; column <= columns; column++) {
                        row.add(result.getString(column));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }

        private String column(String table, String column, String metadata) throws SQLException {
            if (!List.of("extra", "is_nullable", "column_default", "column_comment", "collation_name", "column_type")
                    .contains(metadata)) {
                throw new IllegalArgumentException("Unsupported column metadata: " + metadata);
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + metadata
                    + " FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
                statement.setString(1, schema);
                statement.setString(2, table);
                statement.setString(3, column);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).as("%s.%s exists", table, column).isTrue();
                    return result.getString(1);
                }
            }
        }

        private Map<String, List<List<String>>> businessRows() throws SQLException {
            Map<String, List<List<String>>> snapshot = new TreeMap<>();
            for (String table : BUSINESS_TABLES) {
                if (tableExists(table)) {
                    snapshot.put(table, rows("SELECT * FROM `" + table + "` ORDER BY 1"));
                }
            }
            return snapshot;
        }

        private boolean tableExists(String table) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1) == 1;
                }
            }
        }

        private List<List<String>> columnDefinitions() throws SQLException {
            return rows("SELECT table_name, column_name, column_type, is_nullable, column_default, extra, column_comment "
                    + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                    + "AND table_name <> 'flyway_schema_history' ORDER BY table_name, ordinal_position");
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.close();
            } finally {
                dropSchema(serverUrl, user, password, schema);
            }
        }

        private static void dropSchema(String serverUrl, String user, String password, String schema) throws SQLException {
            if (!schema.matches("takeout_migration_test_[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Refusing to drop a database not owned by this test");
            }
            try (Connection admin = DriverManager.getConnection(serverUrl, user, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }
}
