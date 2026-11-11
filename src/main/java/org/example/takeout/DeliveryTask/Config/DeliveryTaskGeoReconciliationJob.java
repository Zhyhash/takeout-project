package org.example.takeout.DeliveryTask.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "delivery.geo.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DeliveryTaskGeoReconciliationJob {

    private final DeliveryTaskGeoService geoService;

    @Scheduled(
            fixedDelayString = "${delivery.geo.reconciliation.fixed-delay-ms:600000}",
            initialDelayString = "${delivery.geo.reconciliation.initial-delay-ms:600000}")
    public void reconcile() {
        try {
            geoService.reconcileAllAvailableTasksGeo();
        } catch (RuntimeException e) {
            log.error("可接配送任务 GEO 对账任务失败，等待下次调度重试", e);
        }
    }
}
