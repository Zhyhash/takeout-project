package org.example.takeout.Common.Redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisOrderIdempotencyStore {

    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCEEDED_PREFIX = "SUCCEEDED:";

    private final StringRedisTemplate redisTemplate;

    public RedisOrderIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String get(Long userId, String requestId) {
        return redisTemplate.opsForValue().get(buildKey(userId, requestId));
    }

    public boolean tryMarkProcessing(
            Long userId,
            String requestId,
            Duration ttl
    ) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                buildKey(userId, requestId),
                PROCESSING,
                ttl
        );

        return Boolean.TRUE.equals(success);
    }

    public void markSucceeded(
            Long userId,
            String requestId,
            Long orderId,
            Duration ttl
    ) {
        redisTemplate.opsForValue().set(
                buildKey(userId, requestId),
                SUCCEEDED_PREFIX + orderId,
                ttl
        );
    }

    public void clear(Long userId, String requestId) {
        redisTemplate.delete(buildKey(userId, requestId));
    }

    public String buildKey(Long userId, String requestId) {
        return "idempotency:order:create:"
                + userId
                + ":"
                + requestId;
    }
}
