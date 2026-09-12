package org.example.takeout.CacheInvalidationTask.Service;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.CacheInvalidationTask.Enum.CacheInvalidationTaskStatus;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Product.Cache.ProductCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class CacheInvalidationTaskServiceIntegrationTest {

    private static final String CACHE_KEY = "product:detail:transaction-test";

    @Autowired
    private CacheInvalidationTaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ProductCacheService productCacheService;

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
    void createPendingRejectsCallWithoutExistingTransaction() {
        assertThrows(
                IllegalTransactionStateException.class,
                () -> taskService.createPending(CACHE_KEY)
        );

        assertEquals(0, taskCount());
    }

    @Test
    void createPendingCommitsWithOuterTransaction() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(
                ignored -> taskService.createPending(CACHE_KEY)
        );

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
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT next_retry_time FROM cache_invalidation_task",
                LocalDateTime.class
        ));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT created_time FROM cache_invalidation_task",
                LocalDateTime.class
        ));
    }

    @Test
    void createPendingRollsBackWithOuterTransaction() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            taskService.createPending(CACHE_KEY);
            status.setRollbackOnly();
        });

        assertEquals(0, taskCount());
    }

    @Test
    void businessWriteAndOutboxRollBackTogether() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "INSERT INTO cache_invalidation_business_write_test (id, business_value) VALUES (?, ?)",
                    1,
                    "business-change"
            );
            taskService.createPending(CACHE_KEY);
            status.setRollbackOnly();
        });

        assertEquals(0, businessWriteCount());
        assertEquals(0, taskCount());
    }

    @Test
    void retryPendingTasksMarksTaskSuccessWhenRedisDeleteSucceeds() {
        insertTask(
                CacheInvalidationTaskStatus.PENDING.getCode(),
                0,
                LocalDateTime.now().minusSeconds(1)
        );

        taskService.retryPendingTasks();

        assertEquals(CacheInvalidationTaskStatus.SUCCESS.getCode(), taskStatus());
        assertEquals(0, retryCount());
    }

    @Test
    void retryPendingTasksRecordsFailuresAndMarksTaskFailedAfterFifthAttempt() {
        RedisCacheUnavailableException redisFailure =
                new RedisCacheUnavailableException("test Redis failure", new RuntimeException());
        doThrow(redisFailure).when(productCacheService).delete(CACHE_KEY);

        LocalDateTime retryTime = LocalDateTime.now().minusSeconds(1);
        jdbcTemplate.update("""
                INSERT INTO cache_invalidation_task
                    (cache_key, status, retry_count, next_retry_time)
                VALUES (?, ?, ?, ?)
                """, CACHE_KEY, CacheInvalidationTaskStatus.PENDING.getCode(), 0, retryTime);

        for (int attempt = 1; attempt <= 5; attempt++) {
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
                    attempt < 5
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
        doThrow(redisFailure).when(productCacheService).delete(CACHE_KEY);
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
        jdbcTemplate.update("""
                INSERT INTO cache_invalidation_task
                    (cache_key, status, retry_count, next_retry_time)
                VALUES (?, ?, ?, ?)
                """, CACHE_KEY, status, retryCount, nextRetryTime);
    }

    private int taskStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM cache_invalidation_task WHERE cache_key = ?",
                Integer.class,
                CACHE_KEY
        );
    }

    private int retryCount() {
        return jdbcTemplate.queryForObject(
                "SELECT retry_count FROM cache_invalidation_task WHERE cache_key = ?",
                Integer.class,
                CACHE_KEY
        );
    }

    private LocalDateTime nextRetryTime() {
        return jdbcTemplate.queryForObject(
                "SELECT next_retry_time FROM cache_invalidation_task WHERE cache_key = ?",
                LocalDateTime.class,
                CACHE_KEY
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
