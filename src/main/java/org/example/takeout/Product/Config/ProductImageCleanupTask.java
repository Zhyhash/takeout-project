package org.example.takeout.Product.Config;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Product.Service.ProductImageCleanupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductImageCleanupTask {

    private final ProductImageCleanupService productImageCleanupService;

    @Scheduled(fixedDelay = 1000*60*60*12)
    public void cleanupOrphanImages() {
        productImageCleanupService.cleanupOrphanImages();
    }
}
