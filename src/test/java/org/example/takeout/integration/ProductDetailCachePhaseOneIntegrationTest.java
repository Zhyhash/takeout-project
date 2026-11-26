package org.example.takeout.integration;

import org.example.takeout.Product.Config.ProductImageCleanupService;

import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.CacheInvalidationTask.Enum.CacheInvalidationTaskStatus;
import org.example.takeout.CacheInvalidationTask.Service.CacheInvalidationTaskService;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Common.Redis.RedisCacheClient;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.VO.ProductVO;
import org.example.takeout.dataFactory.TestDataFactory;
import org.example.takeout.testsupport.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic cache/database race coverage. Run only with the dedicated
 * reliability-lab MySQL and Redis ports supplied as JVM properties.
 */
@SpringBootTest
@ActiveProfiles("redis-test")
@EnabledIfSystemProperty(named = "takeout.lab.enabled", matches = "true")
@EnabledIfSystemProperty(named = "takeout.test.mysql.port", matches = "(?!3306$)\\d+")
@EnabledIfSystemProperty(named = "spring.data.redis.port", matches = "(?!6379$)\\d+")
@MockitoBean(types = ProductImageCleanupService.class)
class ProductDetailCachePhaseOneIntegrationTest {

    private static final BigDecimal OLD_PRICE = new BigDecimal("19.90");
    private static final BigDecimal NEW_PRICE = new BigDecimal("29.90");
    private static final long TEST_DATA_BASE = 9_000_000_000_000L;

    private final ProductService productService;
    private final CacheInvalidationTaskService cacheInvalidationTaskService;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final CategoryMapper categoryMapper;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private RedisCacheClient redisCacheClient;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    private Long merchantId;
    private Long categoryId;
    private Long productId;
    private String productCacheKey;
    private boolean redisAvailable;

    @Autowired
    ProductDetailCachePhaseOneIntegrationTest(
            ProductService productService,
            CacheInvalidationTaskService cacheInvalidationTaskService,
            ProductMapper productMapper,
            MerchantMapper merchantMapper,
            CategoryMapper categoryMapper,
            StringRedisTemplate redisTemplate,
            JdbcTemplate jdbcTemplate
    ) {
        this.productService = productService;
        this.cacheInvalidationTaskService = cacheInvalidationTaskService;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.categoryMapper = categoryMapper;
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        assertEquals(
                "takeout_integration_test",
                jdbcTemplate.queryForObject("SELECT DATABASE()", String.class),
                "the phase-one race test must use the dedicated integration database"
        );
        RedisTestSupport.assumeRedisAvailable(redisTemplate);
        redisAvailable = true;
        long suffix = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000L);
        merchantId = TEST_DATA_BASE + suffix;
        categoryId = merchantId + 1;
        productId = merchantId + 2;
        productCacheKey = RedisKeyConstant.PRODUCT_DETAIL + productId;

        merchantMapper.insert(TestDataFactory.createOpenMerchant(merchantId));
        insertCategory();

        Product product = TestDataFactory.createProduct(
                productId, "phase-one-cache-race-" + suffix, 10, merchantId);
        product.setCategoryId(categoryId);
        product.setPrice(OLD_PRICE);
        product.setDescription("reliability lab cache race fixture");
        productMapper.insert(product);

        redisTemplate.delete(productCacheKey);
    }

    @AfterEach
    void tearDown() {
        MerchantContextHolder.clear();
        if (redisAvailable && productCacheKey != null) {
            redisTemplate.delete(productCacheKey);
            redisTemplate.delete("Lock:" + productCacheKey);
            jdbcTemplate.update(
                    "DELETE FROM cache_invalidation_task WHERE cache_key = ?",
                    productCacheKey
            );
        }
        if (merchantId != null) {
            jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId);
            jdbcTemplate.update("DELETE FROM category WHERE merchant_id = ?", merchantId);
            merchantMapper.deleteById(merchantId);
        }
        redisAvailable = false;
    }

    @Test
    void delayedRebuildCanRefillStaleDataThenDuplicateTasksAreIdempotentlyRetried() throws Exception {
        CountDownLatch oldSnapshotReachedCacheWrite = new CountDownLatch(1);
        CountDownLatch allowOldSnapshotCacheWrite = new CountDownLatch(1);

        doAnswer(invocation -> {
            ProductDetailCacheDTO serializedProduct = invocation.getArgument(0);
            if (productId.equals(serializedProduct.getId())
                    && OLD_PRICE.compareTo(serializedProduct.getPrice()) == 0) {
                oldSnapshotReachedCacheWrite.countDown();
                assertTrue(
                        allowOldSnapshotCacheWrite.await(8, TimeUnit.SECONDS),
                        "timed out waiting to release the old cache snapshot"
                );
            }
            return invocation.callRealMethod();
        }).when(objectMapper).writeValueAsString(any(ProductDetailCacheDTO.class));

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<ProductVO> inFlightRead = readerExecutor.submit(
                    () -> withMerchantIdentity(() -> productService.getProductDetail(productId)));

            assertTrue(
                    oldSnapshotReachedCacheWrite.await(8, TimeUnit.SECONDS),
                    "cache rebuild did not reach the controlled pre-write point"
            );

            Future<?> productUpdate = updateExecutor.submit(() -> withMerchantIdentity(() -> {
                UpdateProductDTO update = new UpdateProductDTO();
                update.setPrice(NEW_PRICE);
                update.setVersion(0);
                productService.updateProduct(productId, update);
                return null;
            }));
            productUpdate.get(8, TimeUnit.SECONDS);
            assertNull(updateExecutor.submit(MerchantContextHolder::getMerchantId)
                    .get(5, TimeUnit.SECONDS));

            assertEquals(
                    0,
                    NEW_PRICE.compareTo(jdbcTemplate.queryForObject(
                            "SELECT price FROM product WHERE id = ?", BigDecimal.class, productId))
            );
            assertNull(
                    redisTemplate.opsForValue().get(productCacheKey),
                    "afterCommit invalidation should have deleted the cache key"
            );
            assertEquals(
                    CacheInvalidationTaskStatus.PENDING.getCode(),
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM cache_invalidation_task WHERE cache_key = ?",
                            Integer.class,
                            productCacheKey
                    ),
                    "the persisted invalidation remains eligible for its retry"
            );

            allowOldSnapshotCacheWrite.countDown();
            assertEquals(0, OLD_PRICE.compareTo(inFlightRead.get(8, TimeUnit.SECONDS).getPrice()));
            assertNull(readerExecutor.submit(MerchantContextHolder::getMerchantId)
                    .get(5, TimeUnit.SECONDS));

            ProductVO staleCacheHit = productService.getProductDetail(productId);
            assertEquals(
                    0,
                    OLD_PRICE.compareTo(staleCacheHit.getPrice()),
                    "the accepted final-consistency window permits this in-flight reader to refill its old snapshot"
            );

            long normalTtlSeconds = redisTemplate.getExpire(productCacheKey, TimeUnit.SECONDS);
            assertTrue(normalTtlSeconds > 0 && normalTtlSeconds <= 70 * 60,
                    "the rebuilt cache value should retain its configured finite TTL");

            int unrelatedDueTasks = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM cache_invalidation_task
                    WHERE status = ? AND next_retry_time <= ? AND cache_key <> ?
                    """, Integer.class,
                    CacheInvalidationTaskStatus.PENDING.getCode(),
                    LocalDateTime.now(),
                    productCacheKey);
            assertEquals(0, unrelatedDueTasks,
                    "the isolated lab database must not contain unrelated due invalidation work");

            LocalDateTime dueTime = LocalDateTime.now().minusSeconds(1);
            assertEquals(1, jdbcTemplate.update("""
                    UPDATE cache_invalidation_task SET next_retry_time = ?
                    WHERE cache_key = ? AND status = ?
                    """, dueTime, productCacheKey, CacheInvalidationTaskStatus.PENDING.getCode()));
            jdbcTemplate.update("""
                    INSERT INTO cache_invalidation_task
                        (cache_key, status, retry_count, next_retry_time)
                    VALUES (?, ?, 0, ?)
                    """, productCacheKey, CacheInvalidationTaskStatus.PENDING.getCode(), dueTime);
            assertEquals(2, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM cache_invalidation_task
                    WHERE cache_key = ? AND status = ?
                    """, Integer.class,
                    productCacheKey, CacheInvalidationTaskStatus.PENDING.getCode()));

            cacheInvalidationTaskService.retryPendingTasks();

            verify(redisCacheClient, times(3)).delete(productCacheKey);
            assertNull(redisTemplate.opsForValue().get(productCacheKey));
            assertEquals(2, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM cache_invalidation_task
                    WHERE cache_key = ? AND status = ?
                    """, Integer.class,
                    productCacheKey, CacheInvalidationTaskStatus.SUCCESS.getCode()));

            cacheInvalidationTaskService.retryPendingTasks();

            verify(redisCacheClient, times(3)).delete(productCacheKey);
            assertEquals(0, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM cache_invalidation_task
                    WHERE cache_key = ? AND status = ?
                    """, Integer.class,
                    productCacheKey, CacheInvalidationTaskStatus.PENDING.getCode()));

            ProductVO readAfterCompensation = productService.getProductDetail(productId);
            assertEquals(0, NEW_PRICE.compareTo(readAfterCompensation.getPrice()));
        } finally {
            allowOldSnapshotCacheWrite.countDown();
            readerExecutor.shutdownNow();
            updateExecutor.shutdownNow();
            assertTrue(readerExecutor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(updateExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void redisFailureUsesTheRealMysqlLoaderWithoutRepopulatingRedis() {
        doThrow(new RedisCacheUnavailableException(
                "injected Redis outage", new IllegalStateException("test fault injection")))
                .when(redisCacheClient)
                .get(productCacheKey);

        ProductVO product = productService.getProductDetail(productId);

        assertEquals(0, OLD_PRICE.compareTo(product.getPrice()));
        assertNull(redisTemplate.opsForValue().get(productCacheKey));
        verify(redisCacheClient).get(productCacheKey);
    }

    private void insertCategory() {
        Category category = new Category();
        category.setId(categoryId);
        category.setMerchantId(merchantId);
        category.setCategoryName("phase-one-cache-category-" + categoryId);
        category.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        category.setIsDefault(CategoryDefaultEnum.CLASSIFICATION.getCode());
        categoryMapper.insert(category);
    }

    private <T> T withMerchantIdentity(Callable<T> operation) throws Exception {
        MerchantContextHolder.setMerchantId(merchantId);
        try {
            return operation.call();
        } finally {
            MerchantContextHolder.clear();
        }
    }
}
