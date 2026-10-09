package org.example.takeout.Product.Config;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Product.Service.ProductImageService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductImageCleanupService {

    private final ProductImageService productImageService;

    @Scheduled(fixedDelay = 1000*60*60*12)
    public void cleanupOrphanImages() {
        productImageService.cleanupOrphanImages();
    }
}
