package org.example.takeout.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.example.takeout.DeliveryTask.VO.DeliveryTaskDistanceVO;
import org.example.takeout.Merchant.Config.MerchantSseSchedule;
import org.example.takeout.Product.Config.ProductImageCleanupService;
import org.example.takeout.Rider.Config.RiderLocationCleanupScheduler;
import org.example.takeout.Rider.Enums.RiderStatusEnum;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接单大厅 GEO 异常降级实验：用独立 MySQL 数据库保存真实任务，通过 MockMvc 和 JWT
 * 走完整鉴权、骑手查询、任务 Mapper/Converter 及 JSON 响应链路。GEO 查询和骑手位置由 mock 控制。
 * MySQL 默认连接 localhost:3306 root/root，可通过 takeout.geo-fallback-test.mysql.* 覆盖。
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "jwt.secret=test-secret-key-at-least-32-characters-long!!",
        "jwt.expire-days=7",
        "order.timeout.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryTaskGeoFallbackExperimentIntegrationTest {

    private static final long RIDER_ID = 7301L;
    private static final long FAR_TASK_ID = 7101L;
    private static final long MID_TASK_ID = 7102L;
    private static final long NEAR_TASK_ID = 7103L;
    private static final long CLAIMED_TASK_ID = 7104L;
    private static final long COMPLETED_TASK_ID = 7105L;
    private static final double RIDER_LONGITUDE = 121.4737;
    private static final double RIDER_LATITUDE = 31.2304;
    private static final double SEARCH_RADIUS_KM = 5.0;
    private static final String GEO_FAILURE_MESSAGE = "simulated-delivery-geo-failure";
    private static final Set<Long> EXPECTED_TASK_IDS = Set.of(FAR_TASK_ID, MID_TASK_ID, NEAR_TASK_ID);
    private static TestDatabase database;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JWTUtils jwtUtils;

    @MockitoBean
    private DeliveryTaskGeoService deliveryTaskGeoService;

    @MockitoBean
    private RiderLocationService riderLocationService;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @MockitoBean
    private ProductImageCleanupService productImageCleanupService;

    @MockitoBean
    private RiderLocationCleanupScheduler riderLocationCleanupScheduler;

    @MockitoBean
    private MerchantSseSchedule merchantSseSchedule;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) throws SQLException {
        database = TestDatabase.create();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @BeforeEach
    void seedDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).isEqualTo(database.name());
            return null;
        });

        jdbcTemplate.update("DELETE FROM delivery_task");
        jdbcTemplate.update("DELETE FROM rider");
        jdbcTemplate.update("""
                INSERT INTO rider (id, name, phone, password, status, create_time, is_delete)
                VALUES (?, 'geo_fallback_rider', '138001387301', 'unused-hash', ?, CURRENT_TIMESTAMP, 0)
                """, RIDER_ID, RiderStatusEnum.NORMAL.getCode());
        insertWaitingTask(FAR_TASK_ID, 8101L);
        insertWaitingTask(MID_TASK_ID, 8102L);
        insertWaitingTask(NEAR_TASK_ID, 8103L);
        insertTask(CLAIMED_TASK_ID, 8104L,
                DeliveryTaskEnums.DELIVERING.getCode(), RIDER_ID);
        insertTask(COMPLETED_TASK_ID, 8105L,
                DeliveryTaskEnums.COMPLETED.getCode(), RIDER_ID);

        when(riderLocationService.getRiderLocation(RIDER_ID))
                .thenReturn(java.util.Optional.of(new Point(RIDER_LONGITUDE, RIDER_LATITUDE)));
        when(deliveryTaskGeoService.findNearbyTasks(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(geoResultsInDeliberatelyUnsortedOrder());
    }

    @AfterEach
    void clearRiderContext() {
        RiderContextHolder.clear();
    }

    @AfterAll
    static void dropOwnedDatabase() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("接单大厅正常按附近距离排序，GEO 故障时降级返回完整任务")
    void nearbyOrderingAndGeoFailureFallbackKeepTheAvailableTaskIdsComplete() throws Exception {
        List<TaskState> taskStateBeforeRequests = taskStates();

        MvcResult normalResult = getAvailableTasks(SEARCH_RADIUS_KM)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data[0].taskId").value(NEAR_TASK_ID))
                .andExpect(jsonPath("$.data[1].taskId").value(MID_TASK_ID))
                .andExpect(jsonPath("$.data[2].taskId").value(FAR_TASK_ID))
                .andReturn();
        assertThat(normalResult.getResolvedException()).isNull();
        assertThat(taskStates()).isEqualTo(taskStateBeforeRequests);

        doThrow(new RedisGeoOperationException(GEO_FAILURE_MESSAGE))
                .when(deliveryTaskGeoService)
                .findNearbyTasks(anyDouble(), anyDouble(), anyDouble());

        MvcResult fallbackResult = getAvailableTasks(SEARCH_RADIUS_KM)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andReturn();

        assertThat(fallbackResult.getResolvedException()).isNull();
        JsonNode fallbackTasks = objectMapper.readTree(fallbackResult.getResponse().getContentAsString())
                .path("data");
        List<Long> fallbackTaskIds = fallbackTasks.findValuesAsText("taskId").stream()
                .map(Long::valueOf)
                .toList();
        assertThat(fallbackTasks).hasSize(3);
        assertThat(fallbackTaskIds).hasSize(3);
        assertThat(fallbackTaskIds).doesNotHaveDuplicates();
        assertThat(Set.copyOf(fallbackTaskIds)).isEqualTo(EXPECTED_TASK_IDS);

        String fallbackBody = fallbackResult.getResponse().getContentAsString();
        assertThat(fallbackBody)
                .doesNotContain(RedisGeoOperationException.class.getSimpleName())
                .doesNotContain(GEO_FAILURE_MESSAGE)
                .doesNotContain("stacktrace")
                .doesNotContain("at org.example.takeout");
        assertThat(taskStates()).isEqualTo(taskStateBeforeRequests);

        verify(riderLocationService, times(2)).getRiderLocation(RIDER_ID);
        verify(deliveryTaskGeoService, times(2)).findNearbyTasks(
                RIDER_LONGITUDE, RIDER_LATITUDE, SEARCH_RADIUS_KM);
    }

    @Test
    void requestedRadiusIsForwardedToTheGeoQuery() throws Exception {
        getAvailableTasks(2.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].taskId").value(NEAR_TASK_ID))
                .andExpect(jsonPath("$.data[1].taskId").value(MID_TASK_ID))
                .andExpect(jsonPath("$.data[2].taskId").value(FAR_TASK_ID));

        verify(deliveryTaskGeoService).findNearbyTasks(
                RIDER_LONGITUDE, RIDER_LATITUDE, 2.0);
    }

    @Test
    void riderLocationGeoFailureAlsoReturnsMysqlWaitingTasks() throws Exception {
        doThrow(new RedisGeoOperationException(GEO_FAILURE_MESSAGE))
                .when(riderLocationService).getRiderLocation(RIDER_ID);

        MvcResult result = getAvailableTasks(SEARCH_RADIUS_KM)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode tasks = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        List<Long> taskIds = tasks.findValuesAsText("taskId").stream().map(Long::valueOf).toList();
        assertThat(taskIds).containsExactly(NEAR_TASK_ID, MID_TASK_ID, FAR_TASK_ID);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(GEO_FAILURE_MESSAGE)
                .doesNotContain(RedisGeoOperationException.class.getSimpleName());
        assertThat(result.getResolvedException()).isNull();
        verify(deliveryTaskGeoService, org.mockito.Mockito.never())
                .findNearbyTasks(anyDouble(), anyDouble(), anyDouble());
    }

    private org.springframework.test.web.servlet.ResultActions getAvailableTasks(double radiusKm)
            throws Exception {
        String token = jwtUtils.createToken(RIDER_ID, AuthRole.RIDER);
        return mockMvc.perform(get("/rider/delivery-tasks/available")
                .param("radiusKm", String.valueOf(radiusKm))
                .header("Authorization", "Bearer " + token));
    }

    private List<DeliveryTaskDistanceVO> geoResultsInDeliberatelyUnsortedOrder() {
        return List.of(
                geoResult(CLAIMED_TASK_ID, 0.05),
                geoResult(MID_TASK_ID, 0.4),
                geoResult(COMPLETED_TASK_ID, 0.08),
                geoResult(NEAR_TASK_ID, 0.1));
    }

    private DeliveryTaskDistanceVO geoResult(long taskId, double distanceKm) {
        return new DeliveryTaskDistanceVO(
                String.valueOf(taskId), new Distance(distanceKm, Metrics.KILOMETERS));
    }

    private void insertWaitingTask(long taskId, long orderId) {
        insertTask(taskId, orderId, DeliveryTaskEnums.WAIT_ASSIGN.getCode(), null);
    }

    private void insertTask(long taskId, long orderId, Integer status, Long riderId) {
        jdbcTemplate.update("""
                INSERT INTO delivery_task (
                    id, order_id, rider_id, merchant_name, delivery_reward, status, create_time,
                    receiver_phone, receiver_address, merchant_address, pickup_longitude, pickup_latitude,
                    merchant_phone, receiver_name
                ) VALUES (?, ?, ?, ?, 5.00, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?)
                """, taskId, orderId, riderId, "Shop " + taskId, status,
                "13800138000", "Receiver Road " + taskId,
                "Merchant Road", new java.math.BigDecimal("121.4000000"),
                new java.math.BigDecimal("31.2000000"), "13900139000", "Receiver");
    }

    private List<TaskState> taskStates() {
        return jdbcTemplate.query("SELECT id, rider_id, status FROM delivery_task ORDER BY id",
                (rows, rowNumber) -> new TaskState(
                        rows.getLong("id"), rows.getObject("rider_id", Long.class), rows.getInt("status")));
    }

    private record TaskState(long taskId, Long riderId, int status) {
    }

    private record TestDatabase(String name, String jdbcUrl) implements AutoCloseable {
        private static final String PREFIX = "takeout_geo_fallback_test_";

        private static TestDatabase create() throws SQLException {
            String name = PREFIX + UUID.randomUUID().toString().replace("-", "");
            validateName(name);
            TestDatabase owned = new TestDatabase(name, serverUrl(name));
            try (Connection connection = DriverManager.getConnection(serverUrl(""), username(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
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
                        CREATE TABLE rider (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(255) NOT NULL,
                            phone VARCHAR(255) NOT NULL,
                            password VARCHAR(255) NOT NULL,
                            status INT NOT NULL,
                            balance DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                            total_income DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                            create_time DATETIME NOT NULL,
                            update_time DATETIME NULL,
                            is_delete INT NOT NULL DEFAULT 0
                        ) ENGINE=InnoDB
                        """);
                statement.execute("""
                        CREATE TABLE delivery_task (
                            id BIGINT NOT NULL PRIMARY KEY,
                            order_id BIGINT NOT NULL UNIQUE,
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
            String host = System.getProperty("takeout.geo-fallback-test.mysql.host", "localhost");
            String port = System.getProperty("takeout.geo-fallback-test.mysql.port", "3306");
            return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                    + "?serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
        }

        private static String username() {
            return System.getProperty("takeout.geo-fallback-test.mysql.user", "root");
        }

        private static String password() {
            return System.getProperty("takeout.geo-fallback-test.mysql.password", "root");
        }

        private static void validateName(String name) {
            if (!name.matches(PREFIX + "[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Refusing to access an unowned GEO fallback test database: " + name);
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
