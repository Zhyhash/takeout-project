package org.example.takeout.integration;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.Config.MerchantSseSchedule;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Product.Config.ProductImageCleanupService;
import org.example.takeout.Rider.Config.RiderLocationCleanupScheduler;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.example.takeout.Rider.Service.RiderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "order.timeout.enabled=false",
        "delivery.geo.reconciliation.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryTaskGeoReconciliationIntegrationTest {
    private static final long TASK_ID = 701L;
    private static final long RIDER_ID = 301L;
    private static final int TASK_COUNT = 205;
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static TestDatabase database;

    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private DeliveryTaskMapper deliveryTaskMapper;
    @Autowired private DeliveryTaskGeoService deliveryTaskGeoService;
    @Autowired private DeliveryTaskService deliveryTaskService;
    @MockitoBean private StringRedisTemplate redisTemplate;
    @MockitoBean private RiderService riderService;
    @MockitoBean private RiderLocationService riderLocationService;
    @MockitoBean private OrderCommandService orderCommandService;
    @MockitoBean private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;
    @MockitoBean private ProductImageCleanupService productImageCleanupService;
    @MockitoBean private RiderLocationCleanupScheduler riderLocationCleanupScheduler;
    @MockitoBean private MerchantSseSchedule merchantSseSchedule;

    private GeoOperations<String, String> geoOperations;
    private ZSetOperations<String, String> zSetOperations;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = TestDatabase.create();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).isEqualTo(database.name());
            return null;
        });
        jdbcTemplate.update("DELETE FROM delivery_task");
        geoOperations = org.mockito.Mockito.mock(GeoOperations.class);
        zSetOperations = org.mockito.Mockito.mock(ZSetOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range(anyString(), anyLong(), anyLong())).thenReturn(java.util.Set.of());
        when(geoOperations.add(anyString(), any(Point.class), anyString())).thenReturn(1L);
        when(geoOperations.remove(anyString(), anyString())).thenReturn(1L);
    }

    @AfterEach
    void clearRiderContext() {
        RiderContextHolder.clear();
    }

    @AfterAll
    static void dropOwnedDatabase() throws SQLException {
        if (database != null) database.close();
    }

    @Test
    void taskRowLockPreventsReconciliationFromReaddingAfterClaim() throws Exception {
        insertWaitingTask(TASK_ID);
        CountDownLatch geoAddStarted = new CountDownLatch(1);
        CountDownLatch releaseGeoAdd = new CountDownLatch(1);
        CountDownLatch claimStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Long.toString(TASK_ID).equals(invocation.getArgument(2))) {
                geoAddStarted.countDown();
                assertTrue(releaseGeoAdd.await(WAIT.toSeconds(), TimeUnit.SECONDS));
            }
            return 1L;
        }).when(geoOperations).add(anyString(), any(Point.class), anyString());
        when(riderService.requireActiveRiderId()).thenAnswer(invocation -> {
            claimStarted.countDown();
            return RIDER_ID;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> reconciliation = executor.submit(
                    () -> deliveryTaskGeoService.reconcileAvailableTaskGeo(TASK_ID));
            assertTrue(geoAddStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));
            Future<?> claim = executor.submit(() -> {
                RiderContextHolder.setRiderId(RIDER_ID);
                try {
                    deliveryTaskService.claimTask(TASK_ID);
                } finally {
                    RiderContextHolder.clear();
                }
            });
            assertTrue(claimStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> claim.get(200, TimeUnit.MILLISECONDS));
            releaseGeoAdd.countDown();
            reconciliation.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            claim.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            releaseGeoAdd.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_task WHERE id = ?", Integer.class, TASK_ID))
                .isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rider_id FROM delivery_task WHERE id = ?", Long.class, TASK_ID))
                .isEqualTo(RIDER_ID);
        InOrder redisOrder = inOrder(geoOperations);
        redisOrder.verify(geoOperations).add(
                eq("delivery:available:geo"), any(Point.class), eq(Long.toString(TASK_ID)));
        redisOrder.verify(geoOperations).remove(
                eq("delivery:available:geo"), eq(Long.toString(TASK_ID)));
    }

    @Test
    void reconciliationAfterCommittedClaimRemovesWithoutReadding() {
        insertWaitingTask(TASK_ID);
        when(riderService.requireActiveRiderId()).thenReturn(RIDER_ID);
        RiderContextHolder.setRiderId(RIDER_ID);
        try {
            deliveryTaskService.claimTask(TASK_ID);
        } finally {
            RiderContextHolder.clear();
        }

        deliveryTaskGeoService.reconcileAvailableTaskGeo(TASK_ID);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_task WHERE id = ?", Integer.class, TASK_ID))
                .isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rider_id FROM delivery_task WHERE id = ?", Long.class, TASK_ID))
                .isEqualTo(RIDER_ID);
        verify(geoOperations, never()).add(anyString(), any(Point.class), anyString());
        verify(geoOperations, times(2)).remove(
                "delivery:available:geo", Long.toString(TASK_ID));
    }

    @Test
    void fullReconciliationUsesRealMapperAndRepairsAllWaitingTasks() {
        for (long id = 1; id <= TASK_COUNT; id++) insertWaitingTask(id);
        deliveryTaskGeoService.reconcileAllAvailableTasksGeo();

        verify(deliveryTaskMapper).selectWaitAssignTasks(
                DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        verify(geoOperations, times(TASK_COUNT)).add(
                eq("delivery:available:geo"), any(Point.class), anyString());
        verify(zSetOperations).range(eq("delivery:available:geo"), eq(0L), eq(-1L));
    }

    private void insertWaitingTask(long taskId) {
        jdbcTemplate.update("""
                INSERT INTO delivery_task (
                    id, order_id, rider_id, merchant_name, delivery_reward, status, create_time,
                    accepted_time, delivered_time, update_time, receiver_phone, receiver_address,
                    merchant_address, pickup_longitude, pickup_latitude, merchant_phone, receiver_name
                ) VALUES (?, ?, NULL, 'GEO Shop', 5.00, ?, CURRENT_TIMESTAMP, NULL, NULL, NULL,
                    '13800138000', 'Receiver Road', 'Merchant Road', 121.4737, 31.2304,
                    '13900139000', 'Receiver')
                """, taskId, taskId + 1000, DeliveryTaskEnums.WAIT_ASSIGN.getCode());
    }

    private record TestDatabase(String name, String jdbcUrl) implements AutoCloseable {
        private static final String PREFIX = "takeout_geo_reconciliation_test_";

        private static TestDatabase create() throws SQLException {
            String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
            if (!name.matches(PREFIX + "[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Unexpected GEO reconciliation database name");
            }
            TestDatabase owned = new TestDatabase(name, serverUrl(name));
            try (Connection connection = DriverManager.getConnection(serverUrl(""), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + name
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            try {
                owned.createTables();
                return owned;
            } catch (SQLException | RuntimeException failure) {
                try { owned.close(); } catch (SQLException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private void createTables() throws SQLException {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE delivery_task (
                            id BIGINT NOT NULL PRIMARY KEY,
                            order_id BIGINT NOT NULL,
                            rider_id BIGINT NULL,
                            merchant_name VARCHAR(255) NOT NULL,
                            delivery_reward DECIMAL(10,2) NOT NULL,
                            status INT NOT NULL,
                            create_time DATETIME NOT NULL,
                            accepted_time DATETIME NULL,
                            delivered_time DATETIME NULL,
                            update_time DATETIME NULL,
                            receiver_phone VARCHAR(20) NOT NULL,
                            receiver_address VARCHAR(255) NOT NULL,
                            merchant_address VARCHAR(255) NOT NULL,
                            pickup_longitude DECIMAL(10,7) NULL,
                            pickup_latitude DECIMAL(10,7) NULL,
                            merchant_phone VARCHAR(20) NOT NULL,
                            receiver_name VARCHAR(20) NOT NULL
                        ) ENGINE=InnoDB
                        """);
            }
        }

        private static String serverUrl(String databaseName) {
            String host = System.getProperty("takeout.geo-reconciliation-test.mysql.host", "localhost");
            String port = System.getProperty("takeout.geo-reconciliation-test.mysql.port", "3306");
            return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                    + "?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
        }
        private static String username() {
            return System.getProperty("takeout.geo-reconciliation-test.mysql.user", "root");
        }
        private static String password() {
            return System.getProperty("takeout.geo-reconciliation-test.mysql.password", "root");
        }
        @Override
        public void close() throws SQLException {
            if (!name.matches(PREFIX + "[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Unexpected test database");
            }
            try (Connection connection = DriverManager.getConnection(serverUrl(""), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + name);
            }
        }
    }
}
