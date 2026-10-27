package org.example.takeout.Product.Config;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Product.Service.ProductImageService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 商品图片清理的定时调度入口。
 * 每隔 12 小时调用图片服务，清理未被商品记录引用的过期上传图片。
 */
@Component
@RequiredArgsConstructor
public class ProductImageCleanupService {

    private final ProductImageService productImageService;

    @Scheduled(fixedDelay = 1000*60*60*12)
    public void cleanupOrphanImages() {
        productImageService.cleanupOrphanImages();
    }
}
