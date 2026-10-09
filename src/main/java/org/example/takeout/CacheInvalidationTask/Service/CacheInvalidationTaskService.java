package org.example.takeout.CacheInvalidationTask.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.CacheInvalidationTask.Entity.CacheInvalidationTask;
import org.example.takeout.CacheInvalidationTask.Enum.CacheInvalidationTaskStatus;
import org.example.takeout.CacheInvalidationTask.Mapper.CacheInvalidationTaskMapper;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Product.Cache.RedisCacheClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationTaskService {


    private final CacheInvalidationTaskMapper cacheInvalidationTaskMapper;
    private final RedisCacheClient redisCacheClient;


    private void createPending(String cacheKey) {
        CacheInvalidationTask task = new CacheInvalidationTask();
        task.setCacheKey(cacheKey);
        task.setStatus(CacheInvalidationTaskStatus.PENDING.getCode());
        task.setRetryCount(0);
        task.setNextRetryTime(LocalDateTime.now().plusSeconds(15));

        cacheInvalidationTaskMapper.insert(task);

    }


    public void retryPendingTasks() {
        List<CacheInvalidationTask> cacheInvalidationTasks = cacheInvalidationTaskMapper.selectList(Wrappers.<CacheInvalidationTask>lambdaQuery().
                eq(CacheInvalidationTask::getStatus, CacheInvalidationTaskStatus.PENDING.getCode()).
                le(CacheInvalidationTask::getNextRetryTime, LocalDateTime.now()).
                last("limit 100"));
        for (CacheInvalidationTask task : cacheInvalidationTasks) {
            try {
                redisCacheClient.delete(task.getCacheKey());

                int rows = cacheInvalidationTaskMapper.markSuccess(
                        task.getId(),
                        CacheInvalidationTaskStatus.PENDING.getCode()
                );
                if (rows != 1) {
                    throw new IllegalStateException(
                            "缓存失效任务状态更新失败，cacheKey=" + task.getCacheKey() + ", affectedRows=" + rows);
                }
            } catch (RedisCacheUnavailableException e) {
                int rows = cacheInvalidationTaskMapper.recordShortPeriodRetryFailure(
                        task.getId(),
                        CacheInvalidationTaskStatus.PENDING.getCode(),
                        CacheInvalidationTaskStatus.PENDING.getCode(),
                        CacheInvalidationTaskStatus.FAILED.getCode()
                );

                if (rows != 1) {
                    throw new IllegalStateException(
                            "记录缓存失效任务重试失败次数异常（预期更新 1 行，实际 " + rows + " 行）, cacheKey="
                                    + task.getCacheKey(), e
                    );
                }
            }
        }
    }

    public void retryFailedTasks(){
        List<CacheInvalidationTask> cacheInvalidationTasks = cacheInvalidationTaskMapper.selectList(Wrappers.<CacheInvalidationTask>lambdaQuery().
                eq(CacheInvalidationTask::getStatus, CacheInvalidationTaskStatus.FAILED.getCode()).
                le(CacheInvalidationTask::getNextRetryTime, LocalDateTime.now()).
                last("limit 100"));
        for (CacheInvalidationTask task : cacheInvalidationTasks) {
            try {
                redisCacheClient.delete(task.getCacheKey());
                int rows = cacheInvalidationTaskMapper.markSuccess(
                        task.getId(),
                        CacheInvalidationTaskStatus.FAILED.getCode()
                );
                if (rows != 1) {
                    throw new IllegalStateException(
                            "缓存失效任务状态更新失败，cacheKey=" + task.getCacheKey() + ", affectedRows=" + rows);
                }
            }catch (RedisCacheUnavailableException e){
                int rows = cacheInvalidationTaskMapper.
                        recordLongIntervalRetryFailure(task.getId(),
                                CacheInvalidationTaskStatus.FAILED.getCode());
                if (rows != 1) {
                    throw new IllegalStateException(
                            "记录缓存失效任务重试失败次数异常（预期更新 1 行，实际 " + rows + " 行）, cacheKey="
                                    + task.getCacheKey(), e
                    );
                }
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requestInvalidation(String cacheKey){
        createPending(cacheKey);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                //快速删除减少窗口
                try {
                    redisCacheClient.delete(cacheKey);
                } catch (RedisCacheUnavailableException e) {
                    log.error("提交后立即删除缓存失败，cacheKey={}", cacheKey, e);
                }
            }
        });
    }
}
