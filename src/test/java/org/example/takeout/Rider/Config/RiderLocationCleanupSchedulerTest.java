package org.example.takeout.Rider.Config;

import org.example.takeout.Common.Exception.RedisGeoOperationException;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiderLocationCleanupSchedulerTest {

    @Mock
    private RiderLocationService riderLocationService;

    @InjectMocks
    private RiderLocationCleanupScheduler scheduler;

    @Test
    void cleanupDelegatesToRiderLocationService() {
        scheduler.cleanupExpiredRiderLocations();

        verify(riderLocationService).cleanupExpiredRiders();
    }

    @Test
    void cleanupFailureDoesNotEscapeScheduledTask() {
        doThrow(new RedisGeoOperationException("redis down"))
                .when(riderLocationService).cleanupExpiredRiders();

        assertDoesNotThrow(scheduler::cleanupExpiredRiderLocations);
    }

    @Test
    void cleanupWaitsFiveMinutesAfterCompletionInsteadOfRunningAtFixedRate()
            throws NoSuchMethodException {
        Scheduled scheduled = RiderLocationCleanupScheduler.class
                .getMethod("cleanupExpiredRiderLocations")
                .getAnnotation(Scheduled.class);

        assertNotNull(scheduled);
        assertEquals(5, scheduled.fixedDelay());
        assertEquals(TimeUnit.MINUTES, scheduled.timeUnit());
        assertEquals(-1, scheduled.fixedRate());
        assertEquals(-1, scheduled.initialDelay());
    }
}
