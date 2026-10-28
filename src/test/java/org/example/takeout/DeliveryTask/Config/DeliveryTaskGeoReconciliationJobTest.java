package org.example.takeout.DeliveryTask.Config;

import org.example.takeout.DeliveryTask.Service.DeliveryTaskGeoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeliveryTaskGeoReconciliationJobTest {

    @Mock
    private DeliveryTaskGeoService geoService;

    @InjectMocks
    private DeliveryTaskGeoReconciliationJob job;

    @Test
    void reconcileDelegatesToGeoService() {
        job.reconcile();

        verify(geoService).reconcileAllAvailableTasksGeo();
    }

    @Test
    void reconcileLogsAndContainsUnexpectedRuntimeFailure() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(geoService).reconcileAllAvailableTasksGeo();

        assertDoesNotThrow(() -> job.reconcile());
        verify(geoService).reconcileAllAvailableTasksGeo();
    }

    @Test
    void scheduleUsesConfigurableTenMinuteDefaults() throws NoSuchMethodException {
        Scheduled scheduled = DeliveryTaskGeoReconciliationJob.class
                .getMethod("reconcile")
                .getAnnotation(Scheduled.class);

        assertEquals("${delivery.geo.reconciliation.fixed-delay-ms:600000}",
                scheduled.fixedDelayString());
        assertEquals("${delivery.geo.reconciliation.initial-delay-ms:600000}",
                scheduled.initialDelayString());
    }
}
