package org.example.takeout.integration;

import org.example.takeout.Common.Redis.RedisLoginAttemptStore;
import org.example.takeout.testsupport.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("redis-test")
class RedisLoginAttemptStoreIntegrationTest {

    private static final long SHORTENED_TTL_SECONDS = 30L;
    private static final long FAILURE_WINDOW_SECONDS = 300L;
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private RedisLoginAttemptStore loginAttemptStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void requireRedis() {
        RedisTestSupport.assumeRedisAvailable(redisTemplate);
    }

    @Test
    void incrementFailureShouldIncrementAndSetTtl() {
        String role = "user";
        String loginName = "lua-test-" + UUID.randomUUID();

        try {
            long firstCount =
                    incrementFailure(role, loginName);

            long secondCount =
                    incrementFailure(role, loginName);

            long remainingSeconds =
                    loginAttemptStore.getRemainingSeconds(role, loginName);

            assertEquals(1L, firstCount);
            assertEquals(2L, secondCount);
            assertTrue(remainingSeconds > 0);
            assertTrue(remainingSeconds <= 300);
        } finally {
            loginAttemptStore.clear(role, loginName);
        }
    }

    @Test
    void failuresBelowThresholdShouldNotResetTtl() {
        String role = "user";
        String loginName = "below-threshold-" + UUID.randomUUID();
        String key = failureKey(role, loginName);

        try {
            assertEquals(1L, incrementFailure(role, loginName));
            assertTrue(Boolean.TRUE.equals(
                    redisTemplate.expire(key, SHORTENED_TTL_SECONDS, TimeUnit.SECONDS)));

            assertEquals(2L, incrementFailure(role, loginName));
            assertEquals(3L, incrementFailure(role, loginName));
            assertEquals(4L, incrementFailure(role, loginName));

            long remainingSeconds =
                    loginAttemptStore.getRemainingSeconds(role, loginName);

            assertTrue(remainingSeconds > 0);
            assertTrue(remainingSeconds <= SHORTENED_TTL_SECONDS);
        } finally {
            loginAttemptStore.clear(role, loginName);
        }
    }

    @Test
    void fifthFailureShouldResetFullLockoutTtl() {
        String role = "user";
        String loginName = "threshold-" + UUID.randomUUID();
        String key = failureKey(role, loginName);

        try {
            for (long expectedCount = 1L; expectedCount <= 4L; expectedCount++) {
                assertEquals(
                        expectedCount,
                        incrementFailure(role, loginName));
            }
            assertTrue(Boolean.TRUE.equals(
                    redisTemplate.expire(key, SHORTENED_TTL_SECONDS, TimeUnit.SECONDS)));

            assertEquals(5L, incrementFailure(role, loginName));

            long remainingSeconds =
                    loginAttemptStore.getRemainingSeconds(role, loginName);

            assertTrue(remainingSeconds > SHORTENED_TTL_SECONDS);
            assertTrue(remainingSeconds <= FAILURE_WINDOW_SECONDS);
        } finally {
            loginAttemptStore.clear(role, loginName);
        }
    }

    private String failureKey(String role, String loginName) {
        return "security:login:fail:" + role + ":" + loginName;
    }

    private long incrementFailure(String role, String loginName) {
        return loginAttemptStore.incrementFailure(role, loginName, MAX_ATTEMPTS);
    }
}
