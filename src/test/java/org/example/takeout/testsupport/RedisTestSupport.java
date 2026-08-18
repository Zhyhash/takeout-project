package org.example.takeout.testsupport;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisTestSupport {

    private static final String HEALTH_CHECK_KEY = "test:takeout:redis:health-check";

    private RedisTestSupport() {
    }

    public static void assumeRedisAvailable(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.hasKey(HEALTH_CHECK_KEY);
        } catch (RuntimeException exception) {
            Assumptions.assumeTrue(
                    false,
                    "Redis integration test skipped: " + exception.getMessage());
        }
    }
}
