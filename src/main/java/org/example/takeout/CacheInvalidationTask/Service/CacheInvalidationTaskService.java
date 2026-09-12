package org.example.takeout.CacheInvalidationTask.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.CacheInvalidationTask.Entity.CacheInvalidationTask;
import org.example.takeout.CacheInvalidationTask.Enum.CacheInvalidationTaskStatus;
import org.example.takeout.CacheInvalidationTask.Mapper.CacheInvalidationTaskMapper;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Product.Cache.ProductCacheService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CacheInvalidationTaskService {


    private final CacheInvalidationTaskMapper cacheInvalidationTaskMapper;
    private final ProductCacheService productCacheService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createPending(String cacheKey) {
        CacheInvalidationTask task = new CacheInvalidationTask();
        task.setCacheKey(cacheKey);
        task.setStatus(CacheInvalidationTaskStatus.PENDING.getCode());
        task.setRetryCount(0);
        task.setNextRetryTime(LocalDateTime.now());

        cacheInvalidationTaskMapper.insert(task);

    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void markPendingTasksSuccess(String cacheKey) {
        int rows = cacheInvalidationTaskMapper.updatePendingToSuccess(
                cacheKey,
                CacheInvalidationTaskStatus.PENDING.getCode()
        );
        //TODO：此处等一下处理，更新key的时候可能有多个匹配项，我们保证最终一致性，对于1~x都有可能
        if (rows == 0) {
            throw new IllegalStateException(
                    "缓存失效任务状态更新失败，cacheKey=" + cacheKey + ", affectedRows=" + rows
            );
        }
    }

    public void retryPendingTasks() {
        List<CacheInvalidationTask> cacheInvalidationTasks = cacheInvalidationTaskMapper.selectList(Wrappers.<CacheInvalidationTask>lambdaQuery().
                eq(CacheInvalidationTask::getStatus, CacheInvalidationTaskStatus.PENDING.getCode()).
                le(CacheInvalidationTask::getNextRetryTime, LocalDateTime.now()).
                last("limit 100"));
        for (CacheInvalidationTask task : cacheInvalidationTasks) {
            try {
                productCacheService.delete(task.getCacheKey());

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
                productCacheService.delete(task.getCacheKey());
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
}
