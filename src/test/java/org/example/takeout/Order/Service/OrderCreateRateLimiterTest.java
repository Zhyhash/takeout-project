package org.example.takeout.Order.Service;

import org.example.takeout.Order.Limited.OrderCreateRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCreateRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private OrderCreateRateLimiter rateLimiter;

    @Test
    void allowsWhenRedisScriptReturnsOne() {
        doReturn(1L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        assertTrue(rateLimiter.tryAcquire(123L));

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("rate:order:create:123")),
                any(), eq("10000"), eq("5"), any(), eq("15000"));
    }

    @Test
    void rejectsWhenRedisScriptReturnsZero() {
        doReturn(0L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        assertFalse(rateLimiter.tryAcquire(123L));
    }

    @Test
    void failsOpenWhenRedisThrows() {
        doThrow(new IllegalStateException("Redis unavailable")).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        assertTrue(rateLimiter.tryAcquire(123L));
    }

    @Test
    void failsOpenWhenRedisReturnsNull() {
        assertTrue(rateLimiter.tryAcquire(123L));
    }
}
