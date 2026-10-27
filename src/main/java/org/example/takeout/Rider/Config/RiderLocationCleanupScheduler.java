package org.example.takeout.Rider.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 过期骑手位置清理调度器。
 * 使用 fixedDelay：每轮清理执行完成后，再等待 5 分钟才触发下一轮，
 * 而不是按固定频率每 5 分钟触发一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiderLocationCleanupScheduler {

    private final RiderLocationService riderLocationService;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void cleanupExpiredRiderLocations() {
        try {
            riderLocationService.cleanupExpiredRiders();
        } catch (RedisGeoOperationException e) {
            // 单轮清理失败不应影响后续调度，等待下一轮再重试。
            log.error("过期骑手位置清理失败，等待下一轮重试", e);
        }
    }
}
