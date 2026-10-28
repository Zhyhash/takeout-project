package org.example.takeout.integration;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.example.takeout.Merchant.Config.MerchantSseSchedule;
import org.example.takeout.Product.Config.ProductImageCleanupService;
import org.example.takeout.Rider.Config.RiderLocationCleanupScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 用真实 Redis 检查全量 GEO 成员差集修复，以及单任务坐标修复。
 * 任务状态由 mock Mapper 提供，事务使用测试 H2；测试只写随机独立 GEO key。
 */
@SpringBootTest(properties = {
        "spring.data.redis.database=15",
        "delivery.geo.reconciliation.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DeliveryTaskGeoRedisReconciliationIntegrationTest {

    private static final String GEO_KEY =
            "test:delivery:available:geo:" + UUID.randomUUID().toString().replace("-", "");
    private static final long VALID_EXISTING_ID = 41_101L;
    private static final long VALID_MISSING_ID = 41_102L;
    private static final long CLAIMED_ID = 41_103L;
    private static final long COMPLETED_ID = 41_104L;
    private static final long MISSING_COORDINATES_ID = 41_105L;
    private static final long MISSING_TASK_ID = 41_106L;
    private static final String INVALID_MEMBER = "not-a-task-id";

    private final Map<Long, DeliveryTask> tasksById = new LinkedHashMap<>();

    @Autowired
    private DeliveryTaskGeoService deliveryTaskGeoService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private DeliveryTaskMapper deliveryTaskMapper;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @MockitoBean
    private ProductImageCleanupService productImageCleanupService;

    @MockitoBean
    private RiderLocationCleanupScheduler riderLocationCleanupScheduler;

    @MockitoBean
    private MerchantSseSchedule merchantSseSchedule;

    @DynamicPropertySource
    static void geoKeyProperty(DynamicPropertyRegistry registry) {
        registry.add("delivery.geo.available-key", () -> GEO_KEY);
    }

    @BeforeEach
    void setUp() {
        assertThat(redisTemplate.execute(
                (org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping()))
                .isEqualTo("PONG");
        redisTemplate.delete(GEO_KEY);

        tasksById.clear();
        DeliveryTask validExisting = waitingTask(
                VALID_EXISTING_ID, new BigDecimal("121.4737"), new BigDecimal("31.2304"));
        DeliveryTask validMissing = waitingTask(
                VALID_MISSING_ID, new BigDecimal("121.4837"), new BigDecimal("31.2404"));
        DeliveryTask claimed = task(
                CLAIMED_ID, DeliveryTaskEnums.DELIVERING.getCode(), 501L,
                new BigDecimal("121.4937"), new BigDecimal("31.2504"));
        DeliveryTask completed = task(
                COMPLETED_ID, DeliveryTaskEnums.COMPLETED.getCode(), 502L,
                new BigDecimal("121.5037"), new BigDecimal("31.2604"));
        DeliveryTask missingCoordinates = waitingTask(MISSING_COORDINATES_ID, null, null);
        tasksById.put(validExisting.getId(), validExisting);
        tasksById.put(validMissing.getId(), validMissing);
        tasksById.put(claimed.getId(), claimed);
        tasksById.put(completed.getId(), completed);
        tasksById.put(missingCoordinates.getId(), missingCoordinates);

        when(deliveryTaskMapper.selectOne(any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeliveryTask> wrapper =
                            (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeliveryTask>)
                                    invocation.getArgument(0);
                    // MyBatis-Plus lazily binds wrapper parameters while rendering SQL.
                    wrapper.getSqlSegment();
                    Object taskId = wrapper.getParamNameValuePairs().values().stream()
                            .filter(Number.class::isInstance)
                            .map(value -> ((Number) value).longValue())
                            .filter(tasksById::containsKey)
                            .findFirst()
                            .orElse(null);
                    return taskId == null ? null : tasksById.get(((Number) taskId).longValue());
                });
        when(deliveryTaskMapper.selectWaitAssignTasks(
                eq(DeliveryTaskEnums.WAIT_ASSIGN.getCode())))
                .thenAnswer(invocation -> {
                    return tasksById.values().stream()
                            .filter(task -> DeliveryTaskEnums.WAIT_ASSIGN.getCode().equals(task.getStatus()))
                            .filter(task -> task.getRiderId() == null)
                            .sorted(Comparator.comparing(DeliveryTask::getId))
                            .toList();
                });

        geoAt(INVALID_MEMBER, 120.0, 30.0);
        geoAt(MISSING_TASK_ID, 120.1, 30.1);
        geoAt(VALID_EXISTING_ID, 121.4737, 31.2304);
        geoAt(CLAIMED_ID, 120.3, 30.3);
        geoAt(COMPLETED_ID, 120.4, 30.4);
        // VALID_MISSING_ID is a MySQL WAIT_ASSIGN task with no GEO member.
    }

    @AfterEach
    void removeOnlyTestGeoKey() {
        redisTemplate.delete(GEO_KEY);
    }

    @Test
    void fullReconciliationRemovesStaleMembersAndRestoresOnlyValidWaitingTasks() {
        deliveryTaskGeoService.reconcileAllAvailableTasksGeo();

        assertThat(redisTemplate.opsForZSet().range(GEO_KEY, 0, -1))
                .containsExactlyInAnyOrder(
                        Long.toString(VALID_EXISTING_ID),
                        Long.toString(VALID_MISSING_ID));

        Point existing = deliveryTaskGeoService.getAvailableTaskLocation(VALID_EXISTING_ID)
                .orElseThrow();
        assertEquals(121.4737, existing.getX(), 1e-4);
        assertEquals(31.2304, existing.getY(), 1e-4);

        Point restored = deliveryTaskGeoService.getAvailableTaskLocation(VALID_MISSING_ID)
                .orElseThrow();
        assertEquals(121.4837, restored.getX(), 1e-4);
        assertEquals(31.2404, restored.getY(), 1e-4);

        assertThat(redisTemplate.opsForZSet().score(GEO_KEY, INVALID_MEMBER)).isNull();
        for (long staleTaskId : new long[]{
                MISSING_TASK_ID, CLAIMED_ID, COMPLETED_ID, MISSING_COORDINATES_ID}) {
            assertThat(deliveryTaskGeoService.getAvailableTaskLocation(staleTaskId)).isEmpty();
        }
    }

    @Test
    void singleTaskReconciliationCorrectsCoordinatesAndRemovesLocationWithoutCoordinates() {
        // Full reconciliation compares membership; coordinate correction belongs to per-task repair.
        geoAt(VALID_EXISTING_ID, 120.2, 30.2);
        geoAt(MISSING_COORDINATES_ID, 120.5, 30.5);

        deliveryTaskGeoService.reconcileAvailableTaskGeo(VALID_EXISTING_ID);
        deliveryTaskGeoService.reconcileAvailableTaskGeo(MISSING_COORDINATES_ID);

        Point corrected = deliveryTaskGeoService.getAvailableTaskLocation(VALID_EXISTING_ID)
                .orElseThrow();
        assertEquals(121.4737, corrected.getX(), 1e-4);
        assertEquals(31.2304, corrected.getY(), 1e-4);
        assertThat(deliveryTaskGeoService.getAvailableTaskLocation(MISSING_COORDINATES_ID)).isEmpty();
    }

    private void geoAt(String member, double longitude, double latitude) {
        redisTemplate.opsForGeo().add(GEO_KEY, new Point(longitude, latitude), member);
    }

    private void geoAt(long taskId, double longitude, double latitude) {
        geoAt(Long.toString(taskId), longitude, latitude);
    }

    private DeliveryTask waitingTask(Long id, BigDecimal longitude, BigDecimal latitude) {
        return task(id, DeliveryTaskEnums.WAIT_ASSIGN.getCode(), null, longitude, latitude);
    }

    private DeliveryTask task(
            Long id, Integer status, Long riderId, BigDecimal longitude, BigDecimal latitude) {
        DeliveryTask task = new DeliveryTask();
        task.setId(id);
        task.setRiderId(riderId);
        task.setStatus(status);
        task.setPickupLongitude(longitude);
        task.setPickupLatitude(latitude);
        return task;
    }
}
