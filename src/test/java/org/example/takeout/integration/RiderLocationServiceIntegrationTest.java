package org.example.takeout.integration;

import org.example.takeout.Common.Utils.Context.RiderContextHolder;
import org.example.takeout.Rider.Config.RiderLocationCleanupScheduler;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.example.takeout.Rider.Service.RiderService;
import org.example.takeout.testsupport.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 使用真实 Redis 验证 GEO Lua 脚本（单元测试 mock 掉了脚本执行）。
 * 独立使用 database 15，避免影响本机开发数据。
 */
@SpringBootTest(properties = {
        "spring.data.redis.database=15",
        "delivery.geo.reconciliation.enabled=false"
})
class RiderLocationServiceIntegrationTest {

    private static final String LOCATION_KEY = "rider:locations";
    private static final String LAST_ACTIVE_KEY = "rider:last_active";
    private static final long STALE_MILLIS = 10 * 60_000L;

    @Autowired
    private RiderLocationService riderLocationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private RiderLocationCleanupScheduler riderLocationCleanupScheduler;

    @MockitoBean
    private RiderService riderService;

    private Long riderId;
    private Long neighbourRiderId;

    @BeforeEach
    void setUp() {
        RedisTestSupport.assumeRedisAvailable(redisTemplate);
        riderId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
        neighbourRiderId = riderId + 1;
        when(riderService.requireActiveRiderId())
                .thenAnswer(invocation -> RiderContextHolder.getRiderId());
        deleteTestEntries();
    }

    @AfterEach
    void tearDown() {
        RiderContextHolder.clear();
        deleteTestEntries();
    }

    @Test
    void updateLocationPersistsGeoPointAndLastActiveScore() {
        reportLocation(riderId, 121.4737, 31.2304);

        Point point = riderLocationService.getRiderLocation(riderId).orElseThrow();
        assertEquals(121.4737, point.getX(), 1e-4);
        assertEquals(31.2304, point.getY(), 1e-4);

        Double lastActive = redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, riderId.toString());
        assertNotNull(lastActive);
        assertTrue(Math.abs(System.currentTimeMillis() - lastActive) < 60_000);
    }

    @Test
    void updateLocationAcceptsRedisGeoLatitudeBoundary() {
        double[][] boundaryCoordinates = {
                {180.0, 85.05112878},
                {-180.0, -85.05112878}
        };
        for (double[] coordinates : boundaryCoordinates) {
            reportLocation(riderId, coordinates[0], coordinates[1]);

            Point point = riderLocationService.getRiderLocation(riderId).orElseThrow();
            assertEquals(coordinates[0], point.getX(), 1e-4);
            assertEquals(coordinates[1], point.getY(), 1e-4);
        }
    }

    @Test
    void removeRiderLocationDeletesGeoPointAndLastActiveScore() {
        reportLocation(riderId, 121.4737, 31.2304);

        riderLocationService.removeRiderLocation(riderId);

        assertTrue(riderLocationService.getRiderLocation(riderId).isEmpty());
        assertNull(redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, riderId.toString()));
    }

    @Test
    void cleanupExpiredRidersRemovesOnlyStaleRiders() {
        reportLocation(riderId, 121.4737, 31.2304);
        reportLocation(neighbourRiderId, 121.4837, 31.2404);
        redisTemplate.opsForZSet().add(
                LAST_ACTIVE_KEY,
                riderId.toString(),
                System.currentTimeMillis() - STALE_MILLIS
        );

        riderLocationService.cleanupExpiredRiders();

        assertTrue(riderLocationService.getRiderLocation(riderId).isEmpty());
        assertNull(redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, riderId.toString()));
        assertTrue(riderLocationService.getRiderLocation(neighbourRiderId).isPresent());
        assertNotNull(redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, neighbourRiderId.toString()));
    }

    private void reportLocation(Long id, double longitude, double latitude) {
        RiderContextHolder.setRiderId(id);
        try {
            riderLocationService.updateLocation(id, longitude, latitude);
        } finally {
            RiderContextHolder.clear();
        }
    }

    private void deleteTestEntries() {
        for (Long id : new Long[]{riderId, neighbourRiderId}) {
            if (id == null) {
                continue;
            }
            redisTemplate.opsForZSet().remove(LOCATION_KEY, id.toString());
            redisTemplate.opsForZSet().remove(LAST_ACTIVE_KEY, id.toString());
        }
    }
}
