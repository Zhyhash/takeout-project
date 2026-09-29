package org.example.takeout.integration;

import org.example.takeout.Order.Limited.OrderCreateRateLimiter;
import org.example.takeout.testsupport.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("redis-test")
class OrderCreateRateLimiterIntegrationTest {

    @Autowired
    private OrderCreateRateLimiter rateLimiter;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void requireRedis() {
        RedisTestSupport.assumeRedisAvailable(stringRedisTemplate);
    }

    @Test
    void rejectsSixthAttemptWithinWindowWithoutLimitingAnotherUser() {
        long firstUserId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE - 1);
        long secondUserId = firstUserId + 1;
        String firstKey = "rate:order:create:" + firstUserId;
        String secondKey = "rate:order:create:" + secondUserId;

        try {
            for (int attempt = 1; attempt <= 5; attempt++) {
                assertTrue(rateLimiter.tryAcquire(firstUserId),
                        "attempt " + attempt + " should be allowed");
            }

            assertFalse(rateLimiter.tryAcquire(firstUserId),
                    "sixth attempt within 10 seconds should be rejected");
            assertTrue(rateLimiter.tryAcquire(secondUserId),
                    "a different user should have a separate limit");
        } finally {
            redisTemplate.delete(firstKey);
            redisTemplate.delete(secondKey);
            stringRedisTemplate.delete(firstKey);
            stringRedisTemplate.delete(secondKey);
        }
    }
}
