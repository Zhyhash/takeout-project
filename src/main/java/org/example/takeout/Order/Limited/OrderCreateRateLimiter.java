package org.example.takeout.Order.Limited;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;

@Slf4j
@Component
public class OrderCreateRateLimiter {
    private static final long WINDOW_MILLIS = 10_000L;
    private static final long LIMIT = 5L;
    private static final long KEY_TTL_MILLIS = 15_000L;

    private static final DefaultRedisScript<Long>RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>(
                    """
                            local key     = KEYS[1]
                            local now     = tonumber(ARGV[1])
                            local window  = tonumber(ARGV[2])
                            local limit   = tonumber(ARGV[3])
                            local member  = ARGV[4]
                            local ttl     = tonumber(ARGV[5])
                            
                            redis.call('ZREMRANGEBYSCORE',key,'-inf',now-window)
                            local count = redis.call('ZCARD',key)
                            if count >= limit then
                                return 0
                            else
                                redis.call('ZADD',key,now,member)
                                redis.call('PEXPIRE',key,ttl)
                                return 1
                            end
                            """
                    ,Long.class);
    private final StringRedisTemplate stringRedisTemplate;

    public OrderCreateRateLimiter(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate = redisTemplate;
    }

    public boolean tryAcquire(Long userId) {
        long now = System.currentTimeMillis();
        String key="rate:order:create:" + userId;
        String member = now + ":" + UUID.randomUUID();
        //执行Lua
        Long result;
        try {
            result = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(WINDOW_MILLIS),
                    String.valueOf(LIMIT),
                    member,
                    String.valueOf(KEY_TTL_MILLIS)
            );
        } catch (Exception e) {
            log.error("订单创建 Redis限流故障，fail-open 放行", e);
            return true;
        }
        if (result == null) {
            log.error("订单创建 Redis 限流返回空结果，fail-open 放行");
            return true;
        }

        return result == 1L;
    }
}
