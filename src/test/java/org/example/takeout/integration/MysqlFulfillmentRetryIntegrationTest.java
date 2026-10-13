package org.example.takeout.integration;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.Service.MerchantService;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Product.Config.ProductImageCleanupService;
import org.example.takeout.Rider.Enums.RiderStatusEnum;
import org.example.takeout.Rider.Service.RiderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * Real MySQL regressions for fulfillment retries that overlap another commit.
 * B performs its real account SELECT, pauses, and resumes only after A commits.
 * Each class run owns a random database; no existing application/test data is used.
 * Optional connection properties: takeout.fulfillment-test.mysql.host/port/user/password
 * (defaults: localhost/3306/root/root). No Redis server is needed for these tests.
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "order.timeout.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MysqlFulfillmentRetryIntegrationTest {

    private static final long MERCHANT_ID = 101L;
    private static final long RIDER_ID = 301L;
    private static final long ORDER_ID = 601L;
    private static final long TASK_ID = 501L;
    private static final int WAIT_SECONDS = 10;
    private static final LocalDateTime ORIGINAL_ACCEPTED_TIME = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
    private static TestDatabase database;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @MockitoSpyBean
    private RiderService riderService;

    @MockitoSpyBean
    private OrderCommandService orderCommandService;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @MockitoBean
    private ProductImageCleanupService productImageCleanupService;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = TestDatabase.create();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @BeforeEach
    void seedAccounts() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).isEqualTo(database.name());
            // The pool starts at RR: only the production service annotation may switch to RC.
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            return null;
        });
        jdbcTemplate.update("DELETE FROM delivery_task");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM rider");
        jdbcTemplate.update("DELETE FROM merchant");
        jdbcTemplate.update("""
                INSERT INTO merchant (id, username, phone, password, merchant_name, address, status)
                VALUES (?, 'retry_merchant', '13900139000', 'unused-hash', 'Retry Shop', 'Merchant Road', 1)
                """, MERCHANT_ID);
        jdbcTemplate.update("""
                INSERT INTO rider (id, name, phone, password, status, create_time, is_delete)
                VALUES (?, 'retry_rider', '13800138001', 'unused-hash', ?, CURRENT_TIMESTAMP, 0)
                """, RIDER_ID, RiderStatusEnum.NORMAL.getCode());
    }

    @AfterEach
    void clearContexts() {
        MerchantContextHolder.clear();
        RiderContextHolder.clear();
    }

    @AfterAll
    static void dropOwnedDatabase() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void repeatedPreparationSeesOrderAndTaskCommittedAfterItsMerchantRead() throws Exception {
        seedOrder(OrderStatusEnum.PREPARING.getCode());
        RetryGate gate = new RetryGate();
        doAnswer(invocation -> {
            // MerchantService already performed its real merchant SELECT in this transaction.
            gate.pauseAfterAccountRead();
            return invocation.callRealMethod();
        }).when(orderCommandService).markReadyByMerchant(ORDER_ID, MERCHANT_ID);

        TaskState committedTask = runReplayAfterCommit(gate,
                () -> asMerchant(() -> merchantService.completePreparation(ORDER_ID)));

        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.READY.getCode());
        assertThat(committedTask.status()).isEqualTo(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        assertThat(committedTask.riderId()).isNull();
        assertThat(committedTask.createTime()).isNotNull();
        assertThat(committedTask.acceptedTime()).isNull();
        assertThat(committedTask.deliveredTime()).isNull();
        assertSingleUnchangedTask(committedTask);
    }

    @Test
    void repeatedClaimSeesOrderCommittedAfterItsRiderRead() throws Exception {
        seedOrder(OrderStatusEnum.READY.getCode());
        seedTask(DeliveryTaskEnums.WAIT_ASSIGN.getCode(), null, null);
        RetryGate gate = pauseAfterRealRiderRead();

        TaskState committedTask = runReplayAfterCommit(gate,
                () -> asRider(() -> deliveryTaskService.claimTask(TASK_ID)));

        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.DELIVERING.getCode());
        assertThat(committedTask.status()).isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(committedTask.riderId()).isEqualTo(RIDER_ID);
        assertThat(committedTask.acceptedTime()).isNotNull();
        assertThat(committedTask.deliveredTime()).isNull();
        assertSingleUnchangedTask(committedTask);
    }

    @Test
    void repeatedCompletionSeesOrderCommittedAfterItsRiderRead() throws Exception {
        seedOrder(OrderStatusEnum.DELIVERING.getCode());
        seedTask(DeliveryTaskEnums.DELIVERING.getCode(), RIDER_ID, ORIGINAL_ACCEPTED_TIME);
        RetryGate gate = pauseAfterRealRiderRead();

        TaskState committedTask = runReplayAfterCommit(gate,
                () -> asRider(() -> deliveryTaskService.completeDelivery(TASK_ID)));

        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(committedTask.status()).isEqualTo(DeliveryTaskEnums.COMPLETED.getCode());
        assertThat(committedTask.riderId()).isEqualTo(RIDER_ID);
        assertThat(committedTask.acceptedTime()).isEqualTo(ORIGINAL_ACCEPTED_TIME);
        assertThat(committedTask.deliveredTime()).isNotNull();
        assertSingleUnchangedTask(committedTask);
    }

    private RetryGate pauseAfterRealRiderRead() {
        RetryGate gate = new RetryGate();
        doAnswer(invocation -> {
            Object riderId = invocation.callRealMethod();
            gate.pauseAfterAccountRead();
            return riderId;
        }).when(riderService).requireActiveRiderId();
        return gate;
    }

    private TaskState runReplayAfterCommit(RetryGate gate, Runnable action) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> replay = executor.submit(() -> {
            gate.replayThread.set(Thread.currentThread());
            action.run();
        });
        try {
            assertThat(gate.accountRead.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("B reached its real account SELECT before A starts").isTrue();
            action.run();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            TaskState committedTask = taskState();
            gate.resume.countDown();
            // Both invocations must return normally; an exception in B fails via Future.get.
            replay.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return committedTask;
        } finally {
            gate.resume.countDown();
            if (!replay.isDone()) {
                replay.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("no retry worker survives database cleanup").isTrue();
        }
    }

    private void assertReadCommittedTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).isEqualTo(database.name());
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            return null;
        });
        assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");
    }

    private void assertSingleUnchangedTask(TaskState committedTask) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE order_id = ?", Long.class, ORDER_ID)).isEqualTo(1L);
        assertThat(committedTask.orderId()).isEqualTo(ORDER_ID);
        assertThat(taskState()).isEqualTo(committedTask);
    }

    private Integer orderStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", Integer.class, ORDER_ID);
    }

    private TaskState taskState() {
        return jdbcTemplate.queryForObject("SELECT * FROM delivery_task WHERE order_id = ?", (rows, rowNumber) ->
                new TaskState(rows.getLong("id"), rows.getLong("order_id"),
                        rows.getObject("rider_id", Long.class), rows.getInt("status"),
                        time(rows, "create_time"), time(rows, "accepted_time"),
                        time(rows, "delivered_time"), time(rows, "update_time")), ORDER_ID);
    }

    private static LocalDateTime time(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private void seedOrder(int status) {
        jdbcTemplate.update("""
                INSERT INTO orders (id, merchant_id, merchant_name, status, receiver_name,
                    receiver_phone, receiver_address, request_hash)
                VALUES (?, ?, 'Retry Shop', ?, 'Receiver', '13800138000', 'Receiver Road', REPEAT('a', 64))
                """, ORDER_ID, MERCHANT_ID, status);
    }

    private void seedTask(int status, Long riderId, LocalDateTime acceptedTime) {
        jdbcTemplate.update("""
                INSERT INTO delivery_task (id, order_id, rider_id, merchant_name, delivery_reward, status,
                    create_time, accepted_time, receiver_phone, receiver_address,
                    merchant_address, merchant_phone, receiver_name)
                VALUES (?, ?, ?, 'Retry Shop', 5.00, ?, CURRENT_TIMESTAMP, ?,
                    '13800138000', 'Receiver Road', 'Merchant Road', '13900139000', 'Receiver')
                """, TASK_ID, ORDER_ID, riderId, status, acceptedTime);
    }

    private static void asMerchant(Runnable action) {
        MerchantContextHolder.setMerchantId(MERCHANT_ID);
        try {
            action.run();
        } finally {
            MerchantContextHolder.clear();
        }
    }

    private static void asRider(Runnable action) {
        RiderContextHolder.setRiderId(RIDER_ID);
        try {
            action.run();
        } finally {
            RiderContextHolder.clear();
        }
    }

    private final class RetryGate {
        private final AtomicReference<Thread> replayThread = new AtomicReference<>();
        private final CountDownLatch accountRead = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private void pauseAfterAccountRead() throws InterruptedException {
            assertReadCommittedTransaction();
            if (Thread.currentThread() == replayThread.get()) {
                accountRead.countDown();
                assertThat(resume.await(WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("A commits before B continues").isTrue();
            }
        }
    }

    private record TaskState(long id, long orderId, Long riderId, int status, LocalDateTime createTime,
                             LocalDateTime acceptedTime, LocalDateTime deliveredTime, LocalDateTime updateTime) {
    }

    private record TestDatabase(String name, String jdbcUrl) implements AutoCloseable {
        private static final String PREFIX = "takeout_fulfillment_test_";

        private static TestDatabase create() throws SQLException {
            String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
            validateName(name);
            TestDatabase owned = new TestDatabase(name, serverUrl(name));
            try (Connection connection = DriverManager.getConnection(serverUrl(""), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs");
            }
            try {
                owned.createTables();
                return owned;
            } catch (SQLException | RuntimeException failure) {
                try {
                    owned.close();
                } catch (SQLException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private void createTables() throws SQLException {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE merchant (
                            id BIGINT PRIMARY KEY, username VARCHAR(100) NOT NULL, phone VARCHAR(20) NOT NULL,
                            password VARCHAR(100) NOT NULL, address VARCHAR(255) NOT NULL, status TINYINT NOT NULL,
                            create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, merchant_name VARCHAR(255) NOT NULL,
                            picture VARCHAR(255) NULL, merchant_description VARCHAR(255) NULL,
                            opening_time TIME NOT NULL DEFAULT '08:00:00', closing_time TIME NOT NULL DEFAULT '22:00:00',
                            version INT NOT NULL DEFAULT 0
                        ) ENGINE=InnoDB
                        """);
                statement.execute("""
                        CREATE TABLE rider (
                            id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, phone VARCHAR(255) NOT NULL,
                            password VARCHAR(255) NOT NULL, status INT NOT NULL, create_time DATETIME NOT NULL,
                            update_time DATETIME NULL, is_delete INT NOT NULL DEFAULT 0
                        ) ENGINE=InnoDB
                        """);
                statement.execute("""
                        CREATE TABLE orders (
                            id BIGINT PRIMARY KEY, order_no VARCHAR(32) NULL, user_id BIGINT NULL,
                            request_id VARCHAR(64) NULL, request_hash CHAR(64) NOT NULL,
                            merchant_id BIGINT NOT NULL, merchant_name VARCHAR(255) NOT NULL,
                            total_amount DECIMAL(10,2) NULL, status TINYINT NOT NULL,
                            receiver_name VARCHAR(20) NOT NULL, receiver_phone VARCHAR(20) NOT NULL,
                            receiver_address VARCHAR(255) NOT NULL, remark VARCHAR(255) NULL,
                            create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            timeout_cancel_available_at DATETIME NULL, update_time DATETIME NULL,
                            finish_time DATETIME NULL, original_amount DECIMAL(10,2) NULL,
                            discount_amount DECIMAL(10,2) NULL, pay_time DATETIME NULL
                        ) ENGINE=InnoDB
                        """);
                statement.execute("""
                        CREATE TABLE delivery_task (
                            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL UNIQUE,
                            rider_id BIGINT NULL, merchant_name VARCHAR(255) NOT NULL,
                            delivery_reward DECIMAL(10,2) NOT NULL, receiver_name VARCHAR(20) NOT NULL,
                            status INT NOT NULL, create_time DATETIME NOT NULL, accepted_time DATETIME NULL,
                            delivered_time DATETIME NULL, update_time DATETIME NULL,
                            receiver_phone VARCHAR(20) NOT NULL, receiver_address VARCHAR(255) NOT NULL,
                            merchant_address VARCHAR(255) NOT NULL, merchant_phone VARCHAR(20) NOT NULL
                        ) ENGINE=InnoDB
                        """);
            }
        }

        private static String serverUrl(String name) {
            String host = System.getProperty("takeout.fulfillment-test.mysql.host", "localhost");
            String port = System.getProperty("takeout.fulfillment-test.mysql.port", "3306");
            return "jdbc:mysql://" + host + ":" + port + "/" + name
                    + "?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
        }

        private static String username() {
            return System.getProperty("takeout.fulfillment-test.mysql.user", "root");
        }

        private static String password() {
            return System.getProperty("takeout.fulfillment-test.mysql.password", "root");
        }

        private static void validateName(String name) {
            if (!name.matches(PREFIX + "[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Refusing to access an unowned fulfillment test database: " + name);
            }
        }

        @Override
        public void close() throws SQLException {
            validateName(name);
            try (Connection connection = DriverManager.getConnection(serverUrl(""), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + name + "`");
            }
        }
    }
}
