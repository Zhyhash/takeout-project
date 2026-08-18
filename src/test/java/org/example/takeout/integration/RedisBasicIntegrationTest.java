package org.example.takeout.integration;

import org.example.takeout.testsupport.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.example.takeout.testsupport.ConcurrentTestTemplate.runConcurrently;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("redis-test")
class RedisBasicIntegrationTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void requireRedis() {
        RedisTestSupport.assumeRedisAvailable(stringRedisTemplate);
    }

    @Test
    void shouldWriteAndReadString() {
        String key = "test:takeout:redis:basic";

        try {
            stringRedisTemplate.opsForValue().set(key, "hello-redis");

            String value = stringRedisTemplate.opsForValue().get(key);

            assertEquals("hello-redis", value);
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    @Test
    void shouldExpireKeyAfterTtl() throws InterruptedException {
        String key = "test:takeout:redis:ttl";

        try {
            stringRedisTemplate.opsForValue().set(key,"alive",Duration.ofSeconds(2));
            String valueBeforeExpiration =
                    stringRedisTemplate.opsForValue().get(key);

            Long ttl =
                    stringRedisTemplate.getExpire(key);

            assertEquals("alive", valueBeforeExpiration);
            assertNotNull(ttl);
            assertTrue(ttl > 0);

            Thread.sleep(2500);

            String valueAfterExpiration =
                    stringRedisTemplate.opsForValue().get(key);

            assertNull(valueAfterExpiration);
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    @Test
    void shouldOnlySetValueWhenKeyDoesNotExist() {
        String key =
                "test:takeout:redis:nx:" + UUID.randomUUID();

        ValueOperations<String, String> valueOperations =
                stringRedisTemplate.opsForValue();

        Boolean firstAcquire = valueOperations.setIfAbsent(
                key,
                "thread-a",
                Duration.ofSeconds(30)
        );

        Boolean secondAcquire = valueOperations.setIfAbsent(
                key,
                "thread-b",
                Duration.ofSeconds(30)
        );

        String storedValue = valueOperations.get(key);

        assertEquals(Boolean.TRUE, firstAcquire);
        assertNotEquals(Boolean.TRUE, secondAcquire);
        assertEquals("thread-a", storedValue);

        stringRedisTemplate.delete(key);
    }

    @Test
    void shouldAllowOnlyOneThreadToAcquireSameRedisKey() {

        int threadCount = 20;
        String key =
                "test:takeout:idempotency:order:"
                        + UUID.randomUUID();

        Duration ttl = Duration.ofSeconds(30);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        Set<String> participantTokens =
                ConcurrentHashMap.newKeySet();

        try {
            runConcurrently(
                    threadCount,
                    Duration.ofSeconds(10),
                    workerIndex -> {
                        String token = "worker-" + workerIndex;
                        participantTokens.add(token);

                        Boolean acquired =
                                stringRedisTemplate.opsForValue()
                                        .setIfAbsent(
                                                key,
                                                token,
                                                ttl
                                        );

                        if (Boolean.TRUE.equals(acquired)) {
                            successCount.incrementAndGet();
                        } else if (Boolean.FALSE.equals(acquired)) {
                            rejectedCount.incrementAndGet();
                        } else {
                            throw new IllegalStateException(
                                    "setIfAbsent returned null"
                            );
                        }
                    }
            );

            assertEquals(1, successCount.get());
            assertEquals(
                    threadCount - 1,
                    rejectedCount.get()
            );

            String storedToken =
                    stringRedisTemplate.opsForValue().get(key);

            assertNotNull(storedToken);
            assertTrue(participantTokens.contains(storedToken));

            Long remainingTtl =
                    stringRedisTemplate.getExpire(key);

            assertNotNull(remainingTtl);
            assertTrue(remainingTtl > 0);

        } finally {
            stringRedisTemplate.delete(key);
        }
    }

}
