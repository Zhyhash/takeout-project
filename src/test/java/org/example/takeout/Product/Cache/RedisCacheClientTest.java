package org.example.takeout.Product.Cache;

import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheClientTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RedisCacheClient redisCacheClient;

    @Test
    void getReturnsStringValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("product:1")).thenReturn("{\"id\":1}");

        assertEquals("{\"id\":1}", redisCacheClient.get("product:1"));
    }

    @Test
    void setWritesStringValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        redisCacheClient.set("product:1", "{\"id\":1}", 60, TimeUnit.MINUTES);

        verify(valueOperations).set("product:1", "{\"id\":1}", 60, TimeUnit.MINUTES);
    }

    @Test
    void setReportsRedisFailureAsUnavailableForTheCacheServiceToHandle() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        IllegalStateException redisFailure = new IllegalStateException("Redis write failed");
        doThrow(redisFailure)
                .when(valueOperations)
                .set("product:1", "{\"id\":1}", 60, TimeUnit.MINUTES);

        RedisCacheUnavailableException unavailable = assertThrows(
                RedisCacheUnavailableException.class,
                () -> redisCacheClient.set(
                "product:1",
                "{\"id\":1}",
                60,
                TimeUnit.MINUTES
                ));

        assertSame(redisFailure, unavailable.getCause());
        assertTrue(unavailable.getMessage().contains("product:1"));
        verify(valueOperations).set("product:1", "{\"id\":1}", 60, TimeUnit.MINUTES);
    }

    @Test
    void deleteRemovesKey() {
        redisCacheClient.delete("product:1");

        verify(redisTemplate).delete("product:1");
    }

    @Test
    void tryLockStoresUniqueTokenWithExpiration() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                "lock:product:1", "request-token", 10, TimeUnit.SECONDS))
                .thenReturn(true);

        assertTrue(redisCacheClient.tryLock(
                "lock:product:1", "request-token", 10, TimeUnit.SECONDS));
    }

    @Test
    void unlockReturnsTrueOnlyWhenLuaDeletesMatchingToken() {
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList("lock:product:1")),
                eq("request-token")))
                .thenReturn(1L, 0L);

        assertTrue(redisCacheClient.unlock("lock:product:1", "request-token"));
        assertFalse(redisCacheClient.unlock("lock:product:1", "request-token"));
    }
}
