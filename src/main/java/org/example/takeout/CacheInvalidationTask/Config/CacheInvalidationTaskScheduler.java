package org.example.takeout.CacheInvalidationTask.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.CacheInvalidationTask.Service.CacheInvalidationTaskService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CacheInvalidationTaskScheduler {
    private final CacheInvalidationTaskService cacheInvalidationTaskService;

    @Scheduled(fixedDelay = 10_000)
    public void retryPendingTasks() {
        cacheInvalidationTaskService.retryPendingTasks();
    }

    @Scheduled(fixedDelay = 1000*60*60)
    public void retryFailedTasks() {
        cacheInvalidationTaskService.retryFailedTasks();
    }

}
