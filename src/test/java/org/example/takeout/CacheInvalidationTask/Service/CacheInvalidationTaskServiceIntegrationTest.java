package org.example.takeout.CacheInvalidationTask.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.CacheInvalidationTask.Enum.CacheInvalidationTaskStatus;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Product.Cache.RedisCacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class CacheInvalidationTaskServiceIntegrationTest {

    private static final String CACHE_KEY = "product:detail:transaction-test";

    private final CacheInvalidationTaskService taskService;

    private final JdbcTemplate jdbcTemplate;

    private final PlatformTransactionManager transactionManager;

    @MockitoBean
    private RedisCacheClient redisCacheClient;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @BeforeEach
    void resetTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cache_invalidation_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    cache_key VARCHAR(255) NOT NULL,
                    status TINYINT NOT NULL DEFAULT 0,
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cache_invalidation_business_write_test (
                    id INT PRIMARY KEY,
                    business_value VARCHAR(255) NOT NULL
                )
                """);
        jdbcTemplate.update("DELETE FROM cache_invalidation_task");
        jdbcTemplate.update("DELETE FROM cache_invalidation_business_write_test");
    }

    @Test
    void requestInvalidationRejectsCallWithoutExistingTransaction() {
        assertThrows(
                IllegalTransactionStateException.class,
                () -> taskService.requestInvalidation(CACHE_KEY)
        );

        assertEquals(0, taskCount());
        verify(redisCacheClient, never()).delete(anyString());
    }

    @Test
    void requestInvalidationCommitsTaskAndDeletesCacheOnlyAfterOuterCommit() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);
        LocalDateTime beforeRequest = LocalDateTime.now();

        transactionTemplate.executeWithoutResult(ignored -> {
            taskService.requestInvalidation(CACHE_KEY);

            assertEquals(1, taskCount());
            assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus());
            verify(redisCacheClient, never()).delete(CACHE_KEY);
        });

        verify(redisCacheClient).delete(CACHE_KEY);
        assertEquals(1, taskCount());
        assertEquals(
                CACHE_KEY,
                jdbcTemplate.queryForObject(
                        "SELECT cache_key FROM cache_invalidation_task",
                        String.class
                )
        );
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT status FROM cache_invalidation_task",
                        Integer.class
                )
        );
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT retry_count FROM cache_invalidation_task",
                        Integer.class
                )
        );
        LocalDateTime nextRetryTime = nextRetryTime();
        assertNotNull(nextRetryTime);
        assertTrue(nextRetryTime.isAfter(beforeRequest.plusSeconds(10)));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT created_time FROM cache_invalidation_task",
                LocalDateTime.class
        ));
    }

    @Test
    void requestInvalidationRollsBackWithOuterTransactionWithoutDeletingCache() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            taskService.requestInvalidation(CACHE_KEY);
            verify(redisCacheClient, never()).delete(CACHE_KEY);
            status.setRollbackOnly();
        });

        assertEquals(0, taskCount());
        verify(redisCacheClient, never()).delete(anyString());
    }

    @Test
    void businessWriteAndOutboxRollBackTogetherWithoutDeletingCache() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "INSERT INTO cache_invalidation_business_write_test (id, business_value) VALUES (?, ?)",
                    1,
                    "business-change"
            );
            taskService.requestInvalidation(CACHE_KEY);
            status.setRollbackOnly();
        });

        assertEquals(0, businessWriteCount());
        assertEquals(0, taskCount());
        verify(redisCacheClient, never()).delete(anyString());
    }

    @Test
    void requestInvalidationKeepsCommittedTaskPendingWhenFastDeleteFails() {
        RedisCacheUnavailableException redisFailure =
                new RedisCacheUnavailableException("test Redis failure", new RuntimeException());
        doThrow(redisFailure).when(redisCacheClient).delete(CACHE_KEY);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        assertDoesNotThrow(() -> transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.update(
                    "INSERT INTO cache_invalidation_business_write_test (id, business_value) VALUES (?, ?)",
                    1,
                    "business-change"
            );
            taskService.requestInvalidation(CACHE_KEY);
        }));

        verify(redisCacheClient).delete(CACHE_KEY);
        assertEquals(1, businessWriteCount());
        assertEquals(1, taskCount());
        assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus());
        assertEquals(0, retryCount());
    }

    @Test
    void pendingWorkerDeletesAgainAndMarksTaskSuccessAfterFastDelete() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(
                ignored -> taskService.requestInvalidation(CACHE_KEY)
        );

        verify(redisCacheClient).delete(CACHE_KEY);
        assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus());

        jdbcTemplate.update(
                "UPDATE cache_invalidation_task SET next_retry_time = ? WHERE cache_key = ?",
                LocalDateTime.now().minusSeconds(1),
                CACHE_KEY
        );
        taskService.retryPendingTasks();

        verify(redisCacheClient, times(2)).delete(CACHE_KEY);
        assertEquals(CacheInvalidationTaskStatus.SUCCESS.getCode(), taskStatus());
        assertEquals(0, retryCount());
    }

    @Test
    void retryPendingTasksMarksTaskSuccessWhenRedisDeleteSucceeds() {
        insertTask(
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                LocalDateTime.now().minusSeconds(1)
        );

        taskService.retryPendingTasks();

        verify(redisCacheClient).delete(CACHE_KEY);
        assertEquals(CacheInvalidationTaskStatus.SUCCESS.getCode(), taskStatus());
        assertEquals(0, retryCount());
    }

    @Test
    void retryPendingTasksDoesNotExecutePendingTaskBeforeItsRetryTime() {
        LocalDateTime nextRetryTime = LocalDateTime.now().plusMinutes(1);
        insertTask(
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                nextRetryTime
        );

        taskService.retryPendingTasks();

        verify(redisCacheClient, never()).delete(CACHE_KEY);
        assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus());
        assertEquals(0, retryCount());
        assertTrue(nextRetryTime().isAfter(LocalDateTime.now()));
    }

    @Test
    void retryPendingTasksIgnoresSuccessfulAndFailedTasks() {
        String successKey = CACHE_KEY + ":already-success";
        String failedKey = CACHE_KEY + ":already-failed";
        insertTask(
                successKey,
                CacheInvalidationTaskStatus.SUCCESS.getCode(),
                3,
                LocalDateTime.now().minusSeconds(1)
        );
        insertTask(
                failedKey,
                CacheInvalidationTaskStatus.FAILED.getCode(),
                6,
                LocalDateTime.now().minusSeconds(1)
        );

        taskService.retryPendingTasks();

        verify(redisCacheClient, never()).delete(anyString());
        assertEquals(CacheInvalidationTaskStatus.SUCCESS.getCode(), taskStatus(successKey));
        assertEquals(3, retryCount(successKey));
        assertEquals(CacheInvalidationTaskStatus.FAILED.getCode(), taskStatus(failedKey));
        assertEquals(6, retryCount(failedKey));
    }

    @Test
    void retryPendingTasksDoesNotOverwriteStatusChangedByAnotherThread() {
        insertTask(
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                LocalDateTime.now().minusSeconds(1)
        );

        // 模拟 Redis 删除期间，另一个线程已经把任务改成 FAILED。
        doAnswer(invocation -> {
            jdbcTemplate.update(
                    "UPDATE cache_invalidation_task SET status = ?, retry_count = ? WHERE cache_key = ?",
                    CacheInvalidationTaskStatus.FAILED.getCode(),
                    99,
                    CACHE_KEY
            );
            return null;
        }).when(redisCacheClient).delete(CACHE_KEY);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> taskService.retryPendingTasks()
        );

        assertTrue(exception.getMessage().contains("affectedRows=0"));
        assertEquals(CacheInvalidationTaskStatus.FAILED.getCode(), taskStatus());
        assertEquals(99, retryCount());
    }

    @Test
    void retryPendingTasksContinuesAfterOneRedisDeleteFails() {
        String failedKey = CACHE_KEY + ":batch-failed";
        String succeedingKey = CACHE_KEY + ":batch-succeeding";
        RedisCacheUnavailableException redisFailure =
                new RedisCacheUnavailableException("test Redis failure", new RuntimeException());
        doAnswer(invocation -> {
            if (failedKey.equals(invocation.getArgument(0, String.class))) {
                throw redisFailure;
            }
            return null;
        }).when(redisCacheClient).delete(anyString());

        insertTask(
                failedKey,
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                LocalDateTime.now().minusSeconds(1)
        );
        insertTask(
                succeedingKey,
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                LocalDateTime.now().minusSeconds(1)
        );

        taskService.retryPendingTasks();

        verify(redisCacheClient).delete(failedKey);
        verify(redisCacheClient).delete(succeedingKey);
        assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus(failedKey));
        assertEquals(1, retryCount(failedKey));
        assertEquals(CacheInvalidationTaskStatus.SUCCESS.getCode(), taskStatus(succeedingKey));
        assertEquals(0, retryCount(succeedingKey));
    }

    @Test
    void retryPendingTasksUsesExpectedShortPeriodBackoffSequenceAndKeepsTimeOnSixthFailure() {
        RedisCacheUnavailableException redisFailure =
                new RedisCacheUnavailableException("test Redis failure", new RuntimeException());
        doThrow(redisFailure).when(redisCacheClient).delete(CACHE_KEY);
        insertTask(
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                LocalDateTime.now().minusSeconds(1)
        );

        int[] expectedBackoffSeconds = {10, 30, 60, 120, 300};
        for (int expectedBackoff : expectedBackoffSeconds) {
            LocalDateTime beforeRetry = LocalDateTime.now();

            taskService.retryPendingTasks();

            LocalDateTime actualNextRetryTime = nextRetryTime();
            long actualBackoffSeconds = Duration.between(
                    beforeRetry,
                    actualNextRetryTime
            ).getSeconds();
            assertTrue(
                    actualBackoffSeconds >= expectedBackoff - 1
                            && actualBackoffSeconds <= expectedBackoff + 1,
                    "expected approximately " + expectedBackoff
                            + " seconds, but was " + actualBackoffSeconds
            );
            assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus());

            // 模拟等待到下一个重试窗口，进入下一档退避时间。
            jdbcTemplate.update(
                    "UPDATE cache_invalidation_task SET next_retry_time = ? WHERE cache_key = ?",
                    LocalDateTime.now().minusSeconds(1),
                    CACHE_KEY
            );
        }

        assertEquals(5, retryCount());

        // 第六次失败会转为 FAILED，但不再计算新的 next_retry_time。
        jdbcTemplate.update(
                "UPDATE cache_invalidation_task SET next_retry_time = ? WHERE cache_key = ?",
                LocalDateTime.now().minusSeconds(1),
                CACHE_KEY
        );
        LocalDateTime nextRetryTimeBeforeSixthFailure = nextRetryTime();

        taskService.retryPendingTasks();

        assertEquals(CacheInvalidationTaskStatus.FAILED.getCode(), taskStatus());
        assertEquals(6, retryCount());
        assertEquals(nextRetryTimeBeforeSixthFailure, nextRetryTime());
    }

    @Test
    void retryPendingTasksRecordsFailuresAndMarksTaskFailedAfterSixthAttempt() {
        RedisCacheUnavailableException redisFailure =
                new RedisCacheUnavailableException("test Redis failure", new RuntimeException());
        doThrow(redisFailure).when(redisCacheClient).delete(CACHE_KEY);

        insertTask(
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                LocalDateTime.now().minusSeconds(1)
        );

        for (int attempt = 1; attempt <= 6; attempt++) {
            // 每次把任务重新置为到期，模拟定时任务在下一个重试窗口再次执行。
            jdbcTemplate.update(
                    "UPDATE cache_invalidation_task SET next_retry_time = ? WHERE cache_key = ?",
                    LocalDateTime.now().minusSeconds(1),
                    CACHE_KEY
            );

            taskService.retryPendingTasks();

            assertEquals(
                    attempt,
                    jdbcTemplate.queryForObject(
                            "SELECT retry_count FROM cache_invalidation_task WHERE cache_key = ?",
                            Integer.class,
                            CACHE_KEY
                    )
            );
            assertEquals(
                    attempt < 6
                            ? CacheInvalidationTaskStatus.PENDING.getCode()
                            : CacheInvalidationTaskStatus.FAILED.getCode(),
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM cache_invalidation_task WHERE cache_key = ?",
                            Integer.class,
                            CACHE_KEY
                    )
            );
        }
    }

    @Test
    void retryFailedTasksIncrementsRetryCountWhenRedisDeleteFails() {
        RedisCacheUnavailableException redisFailure =
                new RedisCacheUnavailableException("test Redis failure", new RuntimeException());
        doThrow(redisFailure).when(redisCacheClient).delete(CACHE_KEY);
        insertTask(
                CacheInvalidationTaskStatus.FAILED.getCode(),
                5,
                LocalDateTime.now().minusSeconds(1)
        );

        retryFailedTasks();

        assertEquals(CacheInvalidationTaskStatus.FAILED.getCode(), taskStatus());
        assertEquals(6, retryCount());
        assertTrue(nextRetryTime().isAfter(LocalDateTime.now()));
    }

    @Test
    void retryFailedTasksMarksTaskSuccessWhenRedisDeleteSucceeds() {
        insertTask(
                CacheInvalidationTaskStatus.FAILED.getCode(),
                5,
                LocalDateTime.now().minusSeconds(1)
        );

        retryFailedTasks();

        assertEquals(CacheInvalidationTaskStatus.SUCCESS.getCode(), taskStatus());
        assertEquals(5, retryCount());
    }

    private void retryFailedTasks() {
        taskService.retryFailedTasks();
    }

    private void insertTask(Integer status, int retryCount, LocalDateTime nextRetryTime) {
        insertTask(CACHE_KEY, status, retryCount, nextRetryTime);
    }

    private void insertTask(
            String cacheKey,
            Integer status,
            int retryCount,
            LocalDateTime nextRetryTime
    ) {
        jdbcTemplate.update("""
                INSERT INTO cache_invalidation_task
                    (cache_key, status, retry_count, next_retry_time)
                VALUES (?, ?, ?, ?)
                """, cacheKey, status, retryCount, nextRetryTime);
    }

    private int taskStatus() {
        return taskStatus(CACHE_KEY);
    }

    private int taskStatus(String cacheKey) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM cache_invalidation_task WHERE cache_key = ?",
                Integer.class,
                cacheKey
        );
    }

    private int retryCount() {
        return retryCount(CACHE_KEY);
    }

    private int retryCount(String cacheKey) {
        return jdbcTemplate.queryForObject(
                "SELECT retry_count FROM cache_invalidation_task WHERE cache_key = ?",
                Integer.class,
                cacheKey
        );
    }

    private LocalDateTime nextRetryTime() {
        return nextRetryTime(CACHE_KEY);
    }

    private LocalDateTime nextRetryTime(String cacheKey) {
        return jdbcTemplate.queryForObject(
                "SELECT next_retry_time FROM cache_invalidation_task WHERE cache_key = ?",
                LocalDateTime.class,
                cacheKey
        );
    }

    private int businessWriteCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cache_invalidation_business_write_test",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private int taskCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cache_invalidation_task",
                Integer.class
        );
        return count == null ? 0 : count;
    }
}
