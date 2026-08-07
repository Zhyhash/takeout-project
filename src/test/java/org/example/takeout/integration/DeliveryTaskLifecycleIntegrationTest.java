package org.example.takeout.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Rider.Enums.RiderStatusEnum;
import org.example.takeout.testsupport.ConcurrentTestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class DeliveryTaskLifecycleIntegrationTest {

    private static final long TASK_ID = 501L;
    private static final long ORDER_ID = 601L;
    private static final long RIDER_A_ID = 301L;
    private static final long RIDER_B_ID = 302L;
    private static final Duration CONCURRENT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @Autowired
    private JWTUtils jwtUtils;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        recreateDeliverySchema();
        insertRider(RIDER_A_ID, "rider_a");
        insertRider(RIDER_B_ID, "rider_b");
    }

    @AfterEach
    void clearRiderContext() {
        RiderContextHolder.clear();
    }

    @Test
    void riderVisibilityFollowsClaimAndCompletionLifecycle() throws Exception {
        seedWaitingTask(OrderStatusEnum.READY.getCode());

        // A、B 在抢单前都能看到同一个可抢任务。
        assertAvailableTaskIds(RIDER_A_ID, TASK_ID);
        assertAvailableTaskIds(RIDER_B_ID, TASK_ID);

        // B 抢单后，任务从公共池消失；A 看不到，B 的当前任务中可见。
        performAsRider(patch("/rider/delivery-tasks/{taskId}/claim", TASK_ID), RIDER_B_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertAvailableTaskIds(RIDER_A_ID);
        performAsRider(get("/rider/delivery-tasks/current"), RIDER_A_ID)
                .andExpect(jsonPath("$.data").isEmpty());
        performAsRider(get("/rider/delivery-tasks/current"), RIDER_B_ID)
                .andExpect(jsonPath("$.data[0].taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data[0].deliveryReward").value(5));
        performAsRider(get("/rider/delivery-tasks/{taskId}", TASK_ID), RIDER_A_ID)
                .andExpect(jsonPath("$.code").value(500));
        performAsRider(patch("/rider/delivery-tasks/{taskId}/complete", TASK_ID), RIDER_A_ID)
                .andExpect(jsonPath("$.code").value(500));
        assertThat(taskRiderId()).isEqualTo(RIDER_B_ID);
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.DELIVERING.getCode());

        // B 重复抢单是幂等的，不改变已建立的归属和状态。
        LocalDateTime acceptedTime = taskTimestamp("accepted_time");
        performAsRider(patch("/rider/delivery-tasks/{taskId}/claim", TASK_ID), RIDER_B_ID)
                .andExpect(jsonPath("$.code").value(200));
        assertThat(taskTimestamp("accepted_time")).isEqualTo(acceptedTime);

        // 完成和重复完成均成功；“当前任务”重复查询为空，但历史详情仍可重复读取。
        performAsRider(patch("/rider/delivery-tasks/{taskId}/complete", TASK_ID), RIDER_B_ID)
                .andExpect(jsonPath("$.code").value(200));
        LocalDateTime deliveredTime = taskTimestamp("delivered_time");
        performAsRider(patch("/rider/delivery-tasks/{taskId}/complete", TASK_ID), RIDER_B_ID)
                .andExpect(jsonPath("$.code").value(200));
        assertThat(taskTimestamp("delivered_time")).isEqualTo(deliveredTime);
        performAsRider(get("/rider/delivery-tasks/current"), RIDER_B_ID)
                .andExpect(jsonPath("$.data").isEmpty());
        performAsRider(get("/rider/delivery-tasks/current"), RIDER_B_ID)
                .andExpect(jsonPath("$.data").isEmpty());
        performAsRider(get("/rider/delivery-tasks/{taskId}", TASK_ID), RIDER_B_ID)
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.deliveryReward").value(5))
                .andExpect(jsonPath("$.data.status").value(DeliveryTaskEnums.COMPLETED.getCode()));
        performAsRider(get("/rider/delivery-tasks/{taskId}", TASK_ID), RIDER_B_ID)
                .andExpect(jsonPath("$.data.status").value(DeliveryTaskEnums.COMPLETED.getCode()));

        assertThat(taskRiderId()).isEqualTo(RIDER_B_ID);
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.COMPLETED.getCode());
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
    }

    @Test
    void repeatedCompletionStaysIdempotentAfterOrderAdvancesToFinished() {
        seedCompletedTask(RIDER_A_ID, OrderStatusEnum.FINISHED.getCode());

        Attempt attempt = completeAs(RIDER_A_ID);

        assertThat(attempt.success()).isTrue();
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.COMPLETED.getCode());
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.FINISHED.getCode());
        assertThat(taskDelivered()).isTrue();
    }

    @Test
    void concurrentClaimAllowsExactlyOneRiderAndKeepsTaskAndOrderConsistent() {
        seedWaitingTask(OrderStatusEnum.READY.getCode());

        ConcurrentTestTemplate.TwoTaskResult<Attempt, Attempt> result =
                ConcurrentTestTemplate.runTwoTasks(
                        CONCURRENT_TIMEOUT,
                        () -> claimAs(RIDER_A_ID),
                        () -> claimAs(RIDER_B_ID));

        List<Attempt> attempts = List.of(result.firstResult(), result.secondResult());
        assertThat(attempts).filteredOn(Attempt::success).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.success()).hasSize(1);

        Long winnerId = taskRiderId();
        assertThat(winnerId).isIn(RIDER_A_ID, RIDER_B_ID);
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.DELIVERING.getCode());
        assertThat(taskAccepted()).isTrue();
    }

    @Test
    void concurrentRetriesBySameRiderAreIdempotentForClaimAndCompletion() {
        seedWaitingTask(OrderStatusEnum.READY.getCode());

        ConcurrentTestTemplate.TwoTaskResult<Attempt, Attempt> claims =
                ConcurrentTestTemplate.runTwoTasks(
                        CONCURRENT_TIMEOUT,
                        () -> claimAs(RIDER_A_ID),
                        () -> claimAs(RIDER_A_ID));
        assertThat(List.of(claims.firstResult(), claims.secondResult()))
                .allMatch(Attempt::success);
        assertThat(taskRiderId()).isEqualTo(RIDER_A_ID);
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.DELIVERING.getCode());

        ConcurrentTestTemplate.TwoTaskResult<Attempt, Attempt> completions =
                ConcurrentTestTemplate.runTwoTasks(
                        CONCURRENT_TIMEOUT,
                        () -> completeAs(RIDER_A_ID),
                        () -> completeAs(RIDER_A_ID));
        assertThat(List.of(completions.firstResult(), completions.secondResult()))
                .allMatch(Attempt::success);
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.COMPLETED.getCode());
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.DELIVERED.getCode());
        assertThat(taskDelivered()).isTrue();
    }

    @Test
    void claimRollsBackTaskWhenOrderCannotEnterDelivering() {
        seedWaitingTask(OrderStatusEnum.PAID.getCode());

        Attempt attempt = claimAs(RIDER_A_ID);

        assertThat(attempt.success()).isFalse();
        assertThat(taskRiderId()).isNull();
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.WAIT_ASSIGN.getCode());
        assertThat(taskAccepted()).isFalse();
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.PAID.getCode());
    }

    @Test
    void completionRollsBackTaskWhenOrderCannotEnterDelivered() {
        seedDeliveringTask(RIDER_A_ID, OrderStatusEnum.READY.getCode());

        Attempt attempt = completeAs(RIDER_A_ID);

        assertThat(attempt.success()).isFalse();
        assertThat(taskRiderId()).isEqualTo(RIDER_A_ID);
        assertThat(taskStatus()).isEqualTo(DeliveryTaskEnums.DELIVERING.getCode());
        assertThat(taskDelivered()).isFalse();
        assertThat(orderStatus()).isEqualTo(OrderStatusEnum.READY.getCode());
    }

    private Attempt claimAs(long riderId) {
        return invokeAsRider(riderId, () -> deliveryTaskService.claimTask(TASK_ID));
    }

    private Attempt completeAs(long riderId) {
        return invokeAsRider(riderId, () -> deliveryTaskService.completeDelivery(TASK_ID));
    }

    private Attempt invokeAsRider(long riderId, Runnable action) {
        RiderContextHolder.setRiderId(riderId);
        try {
            action.run();
            return new Attempt(true, null);
        } catch (BusinessException exception) {
            return new Attempt(false, exception.getMessage());
        } finally {
            RiderContextHolder.clear();
        }
    }

    private void assertAvailableTaskIds(long riderId, long... expectedTaskIds) throws Exception {
        MvcResult result = performAsRider(get("/rider/delivery-tasks/available"), riderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode tasks = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("list");
        assertThat(tasks).hasSize(expectedTaskIds.length);
        for (int index = 0; index < expectedTaskIds.length; index++) {
            assertThat(tasks.get(index).path("taskId").asLong()).isEqualTo(expectedTaskIds[index]);
            assertThat(tasks.get(index).path("deliveryReward").decimalValue()).isEqualByComparingTo("5");
        }
    }

    private org.springframework.test.web.servlet.ResultActions performAsRider(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            long riderId) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " +
                jwtUtils.createToken(riderId, AuthRole.RIDER)));
    }

    private void seedWaitingTask(int orderStatus) {
        jdbcTemplate.update("INSERT INTO orders (id, status) VALUES (?, ?)", ORDER_ID, orderStatus);
        jdbcTemplate.update("""
                INSERT INTO delivery_task (
                    id, order_id, rider_id, merchant_name, delivery_reward, status, create_time,
                    receiver_phone, receiver_address, merchant_address, merchant_phone, receiver_name
                ) VALUES (?, ?, NULL, 'Test Shop', 5.00, ?, CURRENT_TIMESTAMP,
                    '13800138000', 'Receiver Road', 'Merchant Road', '13900139000', 'Receiver')
                """, TASK_ID, ORDER_ID, DeliveryTaskEnums.WAIT_ASSIGN.getCode());
    }

    private void seedDeliveringTask(long riderId, int orderStatus) {
        jdbcTemplate.update("INSERT INTO orders (id, status) VALUES (?, ?)", ORDER_ID, orderStatus);
        jdbcTemplate.update("""
                INSERT INTO delivery_task (
                    id, order_id, rider_id, merchant_name, delivery_reward, status, create_time, accepted_time,
                    receiver_phone, receiver_address, merchant_address, merchant_phone, receiver_name
                ) VALUES (?, ?, ?, 'Test Shop', 5.00, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    '13800138000', 'Receiver Road', 'Merchant Road', '13900139000', 'Receiver')
                """, TASK_ID, ORDER_ID, riderId, DeliveryTaskEnums.DELIVERING.getCode());
    }

    private void seedCompletedTask(long riderId, int orderStatus) {
        jdbcTemplate.update("INSERT INTO orders (id, status) VALUES (?, ?)", ORDER_ID, orderStatus);
        jdbcTemplate.update("""
                INSERT INTO delivery_task (
                    id, order_id, rider_id, merchant_name, delivery_reward, status, create_time, accepted_time, delivered_time,
                    receiver_phone, receiver_address, merchant_address, merchant_phone, receiver_name
                ) VALUES (?, ?, ?, 'Test Shop', 5.00, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    '13800138000', 'Receiver Road', 'Merchant Road', '13900139000', 'Receiver')
                """, TASK_ID, ORDER_ID, riderId, DeliveryTaskEnums.COMPLETED.getCode());
    }

    private void insertRider(long riderId, String name) {
        jdbcTemplate.update("""
                INSERT INTO rider (id, name, phone, password, status, create_time, is_delete)
                VALUES (?, ?, ?, 'password', ?, CURRENT_TIMESTAMP, 0)
                """, riderId, name, "13800000" + riderId, RiderStatusEnum.NORMAL.getCode());
    }

    private Long taskRiderId() {
        return jdbcTemplate.queryForObject(
                "SELECT rider_id FROM delivery_task WHERE id = ?", Long.class, TASK_ID);
    }

    private Integer taskStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_task WHERE id = ?", Integer.class, TASK_ID);
    }

    private Integer orderStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", Integer.class, ORDER_ID);
    }

    private boolean taskAccepted() {
        return timestampExists("accepted_time");
    }

    private boolean taskDelivered() {
        return timestampExists("delivered_time");
    }

    private boolean timestampExists(String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE id = ? AND " + column + " IS NOT NULL",
                Integer.class,
                TASK_ID);
        return count != null && count == 1;
    }

    private LocalDateTime taskTimestamp(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM delivery_task WHERE id = ?",
                LocalDateTime.class,
                TASK_ID);
    }

    private void recreateDeliverySchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS delivery_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS orders");
        jdbcTemplate.execute("DROP TABLE IF EXISTS rider");

        jdbcTemplate.execute("""
                CREATE TABLE rider (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    phone VARCHAR(255) NOT NULL,
                    password VARCHAR(255) NOT NULL,
                    status INT NOT NULL,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NULL,
                    is_delete INT NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    order_no VARCHAR(64) NULL,
                    user_id BIGINT NULL,
                    request_id VARCHAR(64) NULL,
                    merchant_id BIGINT NULL,
                    merchant_name VARCHAR(255) NULL,
                    total_amount DECIMAL(10, 2) NULL,
                    status INT NOT NULL,
                    receiver_name VARCHAR(20) NULL,
                    receiver_phone VARCHAR(20) NULL,
                    receiver_address VARCHAR(255) NULL,
                    remark VARCHAR(255) NULL,
                    create_time TIMESTAMP NULL,
                    update_time TIMESTAMP NULL,
                    finish_time TIMESTAMP NULL,
                    original_amount DECIMAL(10, 2) NULL,
                    discount_amount DECIMAL(10, 2) NULL,
                    pay_time TIMESTAMP NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE delivery_task (
                    id BIGINT PRIMARY KEY,
                    order_id BIGINT NOT NULL UNIQUE,
                    rider_id BIGINT NULL,
                    merchant_name VARCHAR(255) NOT NULL,
                    delivery_reward DECIMAL(10, 2) NOT NULL,
                    status INT NOT NULL,
                    create_time TIMESTAMP NOT NULL,
                    accepted_time TIMESTAMP NULL,
                    delivered_time TIMESTAMP NULL,
                    update_time TIMESTAMP NULL,
                    receiver_phone VARCHAR(20) NOT NULL,
                    receiver_address VARCHAR(255) NOT NULL,
                    merchant_address VARCHAR(255) NOT NULL,
                    merchant_phone VARCHAR(20) NOT NULL,
                    receiver_name VARCHAR(20) NOT NULL
                )
                """);
    }

    private record Attempt(boolean success, String message) {
    }
}
