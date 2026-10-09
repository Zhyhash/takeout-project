package org.example.takeout.Product.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductImageService {

    private final ProductMapper productMapper;
    public void cleanupOrphanImages() {
        Path uploadDir = Paths.get("./uploads/products");

        Set<String> candidates = findOrphanCandidates(uploadDir);
        if (candidates.isEmpty()) {
            return;
        }
        List<String> products = productMapper.selectReferencedImageUrls(candidates);
        products.forEach(candidates::remove);
        for (String imageUrl : candidates) {
            String filename = imageUrl.substring("/uploads/products/".length());
            Path path = uploadDir.resolve(filename);

            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.error("删除未使用商品图片失败，imageUrl={}", imageUrl, e);
            }
        }
    }
    private Set<String> findOrphanCandidates(Path uploadDir){
        Set<String> candidates = new HashSet<>();
        Instant expireTime = Instant.now().minus(Duration.ofHours(24));

        try (DirectoryStream<Path> files = Files.newDirectoryStream(uploadDir)) {

            for (Path path : files) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }

                FileTime modifiedTime = Files.getLastModifiedTime(path);

                if (modifiedTime.toInstant().isBefore(expireTime)) {
                    String filename = path.getFileName().toString();
                    candidates.add("/uploads/products/" + filename);
                }
            }

        } catch (IOException e) {
            log.error("扫描商品图片目录失败", e);
        }
        return candidates;
    }

}
