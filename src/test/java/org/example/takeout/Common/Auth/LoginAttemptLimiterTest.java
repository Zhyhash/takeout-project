package org.example.takeout.Common.Auth;

import org.example.takeout.Common.Exception.LoginAttemptStoreException;
import org.example.takeout.Common.Exception.LoginRateLimitException;
import org.example.takeout.Common.Redis.RedisLoginAttemptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptLimiterTest {

    private static final String ROLE = AuthRole.USER;
    private static final String LOGIN_NAME = "user-one";
    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private RedisLoginAttemptStore loginAttemptStore;

    @InjectMocks
    private LoginAttemptLimiter loginAttemptLimiter;

    @Test
    void checkAllowedSkipsTtlLookupWhenBelowThreshold() {
        when(loginAttemptStore.getFailureCount(ROLE, LOGIN_NAME)).thenReturn(4L);

        assertDoesNotThrow(() -> loginAttemptLimiter.checkAllowed(ROLE, LOGIN_NAME));

        verify(loginAttemptStore, never()).getRemainingSeconds(ROLE, LOGIN_NAME);
    }

    @Test
    void checkAllowedPropagatesRateLimitWhenThresholdIsReached() {
        when(loginAttemptStore.getFailureCount(ROLE, LOGIN_NAME)).thenReturn(5L);
        when(loginAttemptStore.getRemainingSeconds(ROLE, LOGIN_NAME)).thenReturn(120L);

        LoginRateLimitException exception = assertThrows(
                LoginRateLimitException.class,
                () -> loginAttemptLimiter.checkAllowed(ROLE, LOGIN_NAME)
        );

        assertEquals("操作过于频繁，请在120秒之后重试", exception.getMessage());
    }

    @Test
    void checkAllowedFailsOpenWhenStoreReadFails() {
        when(loginAttemptStore.getFailureCount(ROLE, LOGIN_NAME))
                .thenThrow(new LoginAttemptStoreException("Redis unavailable"));

        assertDoesNotThrow(() -> loginAttemptLimiter.checkAllowed(ROLE, LOGIN_NAME));
    }

    @Test
    void recordFailureFailsOpenWhenStoreWriteFails() {
        when(loginAttemptStore.incrementFailure(ROLE, LOGIN_NAME, MAX_ATTEMPTS))
                .thenThrow(new LoginAttemptStoreException("Redis unavailable"));

        assertDoesNotThrow(() -> loginAttemptLimiter.recordFailure(ROLE, LOGIN_NAME));

        verify(loginAttemptStore)
                .incrementFailure(ROLE, LOGIN_NAME, MAX_ATTEMPTS);
    }

    @Test
    void clearFailuresFailsOpenWhenStoreDeleteFails() {
        doThrow(new LoginAttemptStoreException("Redis unavailable"))
                .when(loginAttemptStore)
                .clear(ROLE, LOGIN_NAME);

        assertDoesNotThrow(() -> loginAttemptLimiter.clearFailures(ROLE, LOGIN_NAME));
    }
}
