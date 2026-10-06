package org.example.takeout.Product.Config;

import org.example.takeout.Product.Service.ProductImageCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ProductImageCleanupTaskTest {

    @Test
    void shouldInvokeCleanupServiceThroughSpringScheduler() throws InterruptedException {
        ProductImageCleanupService cleanupService = mock(ProductImageCleanupService.class);
        CountDownLatch invoked = new CountDownLatch(1);
        doAnswer(invocation -> {
            invoked.countDown();
            return null;
        }).when(cleanupService).cleanupOrphanImages();

        // 仅注册任务和 mock 服务，避免启动完整应用或访问实际上传目录。
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingTestConfiguration.class);
            context.registerBean(ProductImageCleanupService.class, () -> cleanupService);
            context.registerBean(ProductImageCleanupTask.class);
            context.refresh();

            assertTrue(invoked.await(5, TimeUnit.SECONDS), "Spring 调度器应触发图片回收任务");
            verify(cleanupService).cleanupOrphanImages();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingTestConfiguration {
    }
}
