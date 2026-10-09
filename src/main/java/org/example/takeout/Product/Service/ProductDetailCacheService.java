package org.example.takeout.Product.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.CacheInvalidationTask.Service.CacheInvalidationTaskService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.Cache.RedisCacheClient;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductDetailCacheService {
    private static final String NULL_PRODUCT_CACHE = "__NULL__";
    private static final long PRODUCT_CACHE_LOCK_TTL_SECONDS = 10L;
    private static final int PRODUCT_CACHE_RETRY_COUNT = 5;
    private static final long PRODUCT_CACHE_RETRY_INTERVAL_MILLIS = 100L;
    private final CacheInvalidationTaskService cacheInvalidationTaskService;

    private final RedisCacheClient redisCacheClient;
    private final ObjectMapper objectMapper;

    public ProductDetailCacheDTO getProductDetailCache(Long productId, Supplier<ProductDetailCacheDTO> supplier) {
        String cacheKey = buildProductDetailKey(productId);
        ProductDetailCacheDTO cachedProduct = readProductDetailCache(cacheKey);
        if (cachedProduct != null) {
            return cachedProduct;
        }

        String lockKey = buildProductLockKey(productId);
        String lockToken = UUID.randomUUID().toString();
        boolean locked;

        locked = redisCacheClient.tryLock(
                lockKey,
                lockToken,
                PRODUCT_CACHE_LOCK_TTL_SECONDS,
                TimeUnit.SECONDS
        );

        if (!locked) {
            return resolveProductDetailCacheMiss(productId, cacheKey,
                    lockKey,lockToken,supplier);
        }

        return getProductDetailCacheDTO(cacheKey, lockKey, lockToken, supplier);
    }


    private ProductDetailCacheDTO readProductDetailCache(String cacheKey) {
        String cachedJson = redisCacheClient.get(cacheKey);

        if (!StringUtils.hasText(cachedJson)) {
            return null;
        }
        if (NULL_PRODUCT_CACHE.equals(cachedJson)) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在");
        }

        try {
            // StringRedisTemplate 读取的是 JSON 字符串：在这里反序列化为 DTO。
            ProductDetailCacheDTO cachedProduct = objectMapper.readValue(
                    cachedJson,
                    ProductDetailCacheDTO.class
            );
            if (cachedProduct != null && cachedProduct.getInStock() != null) {
                return cachedProduct;
            }
            log.info("商品缓存缺少 inStock 字段，重新加载 key={}", cacheKey);
        } catch (JacksonException e) {
            log.warn("商品缓存解析失败，删除缓存 key={}", cacheKey, e);
        }

        redisCacheClient.delete(cacheKey);
        return null;
    }

    //NOTE:写入缓存
    private void writeProductDetailCache(String cacheKey,
            ProductDetailCacheDTO dto) {
        if (dto == null) {
            writeNullProductDetailCache(cacheKey);
            return;
        }

        String cacheJson = objectMapper.writeValueAsString(dto);

        try {
            redisCacheClient.set(
                    cacheKey,
                    cacheJson,
                    randomCacheTtlMinutes(50, 70),
                    TimeUnit.MINUTES
            );
        } catch (RedisCacheUnavailableException e) {
            log.warn(
                    "商品详情缓存回填失败，允许业务继续，productId={}",
                    dto.getId(),
                    e
            );
        }
    }

    private void writeNullProductDetailCache(String cacheKey) {
        try {
            redisCacheClient.set(
                    cacheKey,
                    NULL_PRODUCT_CACHE,
                    randomCacheTtlMinutes(2, 5),
                    TimeUnit.MINUTES
            );
        } catch (RedisCacheUnavailableException e) {
            log.warn(
                    "商品详情空值缓存回填失败，允许业务继续，cacheKey={}",
                    cacheKey,
                    e
            );
        }
    }

    //NOTE：没有抢到锁的时候的等待重试
    private ProductDetailCacheDTO resolveProductDetailCacheMiss(Long id, String cacheKey, String lockKey,
                                                                String lockToken,
                                                                Supplier<ProductDetailCacheDTO> supplier) {
        for (int attempt = 0; attempt < PRODUCT_CACHE_RETRY_COUNT; attempt++) {

            try {
                Thread.sleep(PRODUCT_CACHE_RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(
                        ResultCodeEnum.BUSINESS_ERROR,
                        "商品缓存等待被中断"
                );
            }

            //读取缓存并抢锁
            ProductDetailCacheDTO cachedProduct = readProductDetailCache(cacheKey);
            if (cachedProduct != null) {
                return cachedProduct;
            }

            boolean locked = redisCacheClient.tryLock(
                    lockKey,
                    lockToken,
                    PRODUCT_CACHE_LOCK_TTL_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!locked) {
                continue;
            }

            return getProductDetailCacheDTO(cacheKey, lockKey, lockToken, supplier);

        }
        log.warn("等待商品缓存重建超时，拒绝继续回源数据库，productId={}", id);
        throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                "请求超时，请稍后重试");
    }

    private ProductDetailCacheDTO getProductDetailCacheDTO(String cacheKey, String lockKey, String lockToken, Supplier<ProductDetailCacheDTO> supplier) {
        ProductDetailCacheDTO cachedProduct;
        try {
            // 获得锁后再次确认，防止竞争期间其他请求已经完成重建。
            cachedProduct = readProductDetailCache(cacheKey);
            if (cachedProduct != null) {
                return cachedProduct;
            }
            ProductDetailCacheDTO productDetailCacheDTO = supplier.get();
            writeProductDetailCache(cacheKey, productDetailCacheDTO);
            return productDetailCacheDTO;
        } finally {
            redisCacheClient.unlock(lockKey, lockToken);
        }
    }

    public void evictProductDetailCache(Long productId) {
        String cacheKey = buildProductDetailKey(productId);
        cacheInvalidationTaskService.requestInvalidation(cacheKey);
        // TODO Outbox任务聚合优化：
        // 当前允许同一 cacheKey 创建多条 PENDING 任务，Redis 长时间不可用时可能造成任务积压。
        // 后续考虑按 cacheKey 聚合未完成任务，避免重复 INSERT / DEL。
        // 可增加 count 记录同 key 累计失效次数，用于任务权重、异常流量识别或限流/风控。
        // 注意：需要区分历史 SUCCESS 记录与当前活跃任务，不能直接对 cacheKey 做简单 UNIQUE。
    }


    private String buildProductDetailKey(Long id){
        return RedisKeyConstant.PRODUCT_DETAIL + id;
    }

    private String buildProductLockKey(Long id){
        return "Lock:"+RedisKeyConstant.PRODUCT_DETAIL + id;
    }

    private long randomCacheTtlMinutes(long minMinutes, long maxMinutes) {
        if (minMinutes <= 0 || maxMinutes < minMinutes) {
            throw new IllegalArgumentException("缓存TTL范围不正确");
        }

        return ThreadLocalRandom.current()
                .nextLong(minMinutes, maxMinutes + 1);
    }


}
