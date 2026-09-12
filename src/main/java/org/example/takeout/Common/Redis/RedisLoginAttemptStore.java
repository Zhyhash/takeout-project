package org.example.takeout.Common.Redis;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.LoginAttemptStoreException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisLoginAttemptStore {
    private final StringRedisTemplate redisTemplate;
    private static final long FAILURE_WINDOW_SECONDS=300L;
    private static final DefaultRedisScript<Long> INCREMENT_FAILURE_SCRIPT=
            new DefaultRedisScript<>(
                    """
                           local count = redis.call('INCR',KEYS[1])
                           local maxAttempts = tonumber(ARGV[2])
                           
                           if count ==1 or count == maxAttempts then
                            redis.call('expire',KEYS[1],ARGV[1])
                           end
                           
                           return count
                           """
            ,Long.class);

    public long incrementFailure(String role, String loginName,int maxAttempts) {
        String key = buildKey(role, loginName);
        try {
            Long count=redisTemplate.execute(INCREMENT_FAILURE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(FAILURE_WINDOW_SECONDS),
                    String.valueOf(maxAttempts));
            if (count==null) {
                throw new LoginAttemptStoreException("登录失败次数计数异常");
            }

            return count;
        } catch (LoginAttemptStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginAttemptStoreException("Redis登录失败计数操作异常",e);
        }
    }



    public void clear(String role, String loginName) {
        String key = buildKey(role, loginName);

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (deleted == null) {
                throw new LoginAttemptStoreException("登录失败次数清除异常");
            }
        } catch (LoginAttemptStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginAttemptStoreException("Redis登录失败次数清除异常",e);
        }
    }

    //NOTE：获取失败次数
    public long getFailureCount(String role, String loginName){
        String key = buildKey(role, loginName);
        try {
            String count = redisTemplate.opsForValue().get(key);
            if (count == null) {
                return 0;
            }
            return Long.parseLong(count);
        }catch (Exception e){
            throw new LoginAttemptStoreException("读取登录失败次数异常",e);
        }
    }

    //NOTE:获取当前的登录剩余锁定时间
    public long getRemainingSeconds(String role, String loginName){
        String key = buildKey(role, loginName);
        try {
            Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (expire == null) {
                throw new LoginAttemptStoreException("获取登录剩余锁定时间失败：Redis 返回过期时间为 null");
            }
            if (expire == -1) {
                throw new LoginAttemptStoreException("获取登录剩余锁定时间失败：Key 存在但未设置过期时间");
            }
            if (expire == -2) {
                return 0;
            }
            return expire;
        }catch (LoginAttemptStoreException e) {
            throw e;
        }
        catch (Exception e){
            throw new LoginAttemptStoreException("获取登录剩余锁定时间异常",e);
        }
    }
    private String buildKey(String role, String loginName) {
        return "security:login:fail:"+role +":"+loginName;
    }
}
