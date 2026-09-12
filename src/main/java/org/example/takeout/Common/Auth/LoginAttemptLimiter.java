package org.example.takeout.Common.Auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.LoginAttemptStoreException;
import org.example.takeout.Common.Exception.LoginRateLimitException;
import org.example.takeout.Common.Redis.RedisLoginAttemptStore;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public final class LoginAttemptLimiter {
    private final RedisLoginAttemptStore loginAttemptStore;
    private static final int MAX_ATTEMPTS = 5;

    public void checkAllowed(String role, String loginName) {
        try {
            long count = loginAttemptStore.getFailureCount(role, loginName);

            if (count < MAX_ATTEMPTS) {
                return;
            }

            long remainingSeconds =
                    loginAttemptStore.getRemainingSeconds(role, loginName);

            if (remainingSeconds > 0) {
                throw new LoginRateLimitException("操作过于频繁，请在"+
                        remainingSeconds+"秒之后重试");
            }

        } catch (LoginAttemptStoreException e) {
            log.warn(
                    "登录限流 Redis 不可用，执行 fail-open，role={}, loginName={}",
                    role,
                    loginName,
                    e
            );
        }
    }

    public void recordFailure(String role, String loginName) {
        try {
            loginAttemptStore.incrementFailure(role, loginName,MAX_ATTEMPTS);
        } catch (LoginAttemptStoreException e) {
            log.error("Redis登录失败计数操作异常",e);
        }
    }

    public void clearFailures(String role, String loginName) {
        try {
            loginAttemptStore.clear(role, loginName);
        } catch (LoginAttemptStoreException e) {
            log.error("Redis登录失败次数清除异常",e);
        }
    }
}
