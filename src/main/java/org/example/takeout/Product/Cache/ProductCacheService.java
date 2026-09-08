package org.example.takeout.Product.Cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductCacheService {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            throw new RedisCacheUnavailableException("Redis读取失败，key=" + key, e);
        }
    }

    public void set(String key, String value, long ttl, TimeUnit timeUnit) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl, timeUnit);
        } catch (Exception e) {
            log.warn("Redis set failed, key={}", key, e);
        }
    }

    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis delete failed, key={}", key, e);
        }
    }

    public boolean tryLock(String key, String lockToken, long ttl, TimeUnit timeUnit) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().
                            setIfAbsent(key, lockToken, ttl, timeUnit));
        } catch (Exception e) {
            throw new RedisCacheUnavailableException("Redis互斥锁执行失败，key=" + key,e);
        }
    }

    public boolean unlock(String key, String lockToken) {
        try {
            Long result = redisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(key),
                    lockToken
            );
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            log.warn("Redis unlock failed, key={}", key, e);
            return false;
        }
    }
}
