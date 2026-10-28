package org.example.takeout.integration;

import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.example.takeout.DeliveryTask.VO.DeliveryTaskDistanceVO;
import org.example.takeout.testsupport.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 Redis 验证可接取配送任务 GEO 的写入、更新、删除与附近查询。
 * 独立使用 database 15，避免影响本机开发数据。
 */
@SpringBootTest(properties = "spring.data.redis.database=15")
class DeliveryTaskGeoServiceIntegrationTest {

    private static final String TASK_GEO_KEY = "delivery:available:geo";

    @Autowired
    private DeliveryTaskGeoService deliveryTaskGeoService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long taskId;
    private Long neighbourTaskId;

    @BeforeEach
    void setUp() {
        RedisTestSupport.assumeRedisAvailable(redisTemplate);
        taskId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
        neighbourTaskId = taskId + 1;
        deleteTestEntries();
    }

    @AfterEach
    void tearDown() {
        deleteTestEntries();
    }

    @Test
    void addAvailableTaskPersistsGeoPoint() {
        deliveryTaskGeoService.addAvailableTask(taskId, 121.4737, 31.2304);

        Point point = deliveryTaskGeoService.getAvailableTaskLocation(taskId).orElseThrow();
        assertEquals(121.4737, point.getX(), 1e-4);
        assertEquals(31.2304, point.getY(), 1e-4);
    }

    @Test
    void addAvailableTaskUpdatesCoordinatesForExistingTask() {
        deliveryTaskGeoService.addAvailableTask(taskId, 121.4737, 31.2304);

        deliveryTaskGeoService.addAvailableTask(taskId, 121.5000, 31.2500);

        Point point = deliveryTaskGeoService.getAvailableTaskLocation(taskId).orElseThrow();
        assertEquals(121.5000, point.getX(), 1e-4);
        assertEquals(31.2500, point.getY(), 1e-4);
    }

    @Test
    void findNearbyTasksReturnsTasksWithDistance() {
        deliveryTaskGeoService.addAvailableTask(taskId, 121.4737, 31.2304);
        deliveryTaskGeoService.addAvailableTask(neighbourTaskId, 121.4837, 31.2404);

        List<DeliveryTaskDistanceVO> nearbyTasks =
                deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, 5.0);

        assertTrue(nearbyTasks.stream()
                .anyMatch(task -> taskId.toString().equals(task.getTaskId())));
        assertTrue(nearbyTasks.stream()
                .anyMatch(task -> neighbourTaskId.toString().equals(task.getTaskId())));
        assertTrue(nearbyTasks.stream().allMatch(task ->
                Double.isFinite(task.getDistance()) && task.getDistance() >= 0));
    }

    @Test
    void findNearbyTasksExcludesTasksOutsideRadius() {
        deliveryTaskGeoService.addAvailableTask(taskId, 121.4737, 31.2304);
        deliveryTaskGeoService.addAvailableTask(neighbourTaskId, 121.4837, 31.2404);

        List<DeliveryTaskDistanceVO> nearbyTasks =
                deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, 0.5);

        assertTrue(nearbyTasks.stream()
                .anyMatch(task -> taskId.toString().equals(task.getTaskId())));
        assertTrue(nearbyTasks.stream()
                .noneMatch(task -> neighbourTaskId.toString().equals(task.getTaskId())));
    }

    @Test
    void removeAvailableTaskDeletesGeoPoint() {
        deliveryTaskGeoService.addAvailableTask(taskId, 121.4737, 31.2304);

        deliveryTaskGeoService.removeAvailableTask(taskId);

        assertTrue(deliveryTaskGeoService.getAvailableTaskLocation(taskId).isEmpty());
        assertTrue(deliveryTaskGeoService.findNearbyTasks(121.4737, 31.2304, 5.0).stream()
                .noneMatch(task -> taskId.toString().equals(task.getTaskId())));
    }

    private void deleteTestEntries() {
        for (Long id : new Long[]{taskId, neighbourTaskId}) {
            if (id == null) {
                continue;
            }
            redisTemplate.opsForGeo().remove(TASK_GEO_KEY, String.valueOf(id));
        }
    }
}
