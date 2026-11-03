package org.example.takeout.integration;

import org.example.takeout.Order.Limited.OrderCreateRateLimiter;
import org.example.takeout.testsupport.RedisTestSupport;
import org.example.takeout.testsupport.RedisOnlyTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(
        classes = RedisOnlyTestConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
class OrderCreateRateLimiterIntegrationTest {

    @Autowired
    private OrderCreateRateLimiter rateLimiter;

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
            stringRedisTemplate.delete(firstKey);
            stringRedisTemplate.delete(secondKey);
        }
    }
}
