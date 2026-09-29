package org.example.takeout.integration;

import lombok.RequiredArgsConstructor;
import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.CacheInvalidationTask.Enum.CacheInvalidationTaskStatus;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the cache-aside flow against the local MySQL and Redis instances.
 */
@SpringBootTest
@ActiveProfiles("redis-test")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class ProductDetailCacheInvalidationIntegrationTest {

    private static final Long TEST_MERCHANT_ID = 9_021_001L;
    private static final Long TEST_CATEGORY_ID = 9_021_002L;
    private static final Long TEST_PRODUCT_ID = 9_021_003L;
    private static final BigDecimal OLD_PRICE = new BigDecimal("19.90");
    private static final BigDecimal NEW_PRICE = new BigDecimal("29.90");

    private boolean redisAvailable;

    private final ProductService productService;

    private final ProductMapper productMapper;

    private final MerchantMapper merchantMapper;

    private final CategoryMapper categoryMapper;

    private final StringRedisTemplate redisTemplate;

    private final JdbcTemplate jdbcTemplate;

    private final PlatformTransactionManager transactionManager;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @BeforeEach
    void setUp() {
        RedisTestSupport.assumeRedisAvailable(redisTemplate);
        redisAvailable = true;
        deleteTestData();
        merchantMapper.insert(TestDataFactory.createOpenMerchant(TEST_MERCHANT_ID));
        insertCategory();

        Product product = TestDataFactory.createProduct(
                TEST_PRODUCT_ID, "cache-invalidation-product", 10, TEST_MERCHANT_ID);
        product.setCategoryId(TEST_CATEGORY_ID);
        product.setPrice(OLD_PRICE);
        product.setDescription("cache invalidation integration test product");
        productMapper.insert(product);

        MerchantContextHolder.setMerchantId(TEST_MERCHANT_ID);
    }

    @AfterEach
    void tearDown() {
        try {
            if (redisAvailable) {
                deleteTestData();
            }
        } finally {
            MerchantContextHolder.clear();
            redisAvailable = false;
        }
    }

    @Test
    void shouldReloadNewPriceFromMysqlAndRewriteRedisAfterPriceUpdate() {
        String key = RedisKeyConstant.PRODUCT_DETAIL + TEST_PRODUCT_ID;

        // 1. Redis miss -> MySQL query -> Redis write.
        assertNull(redisTemplate.opsForValue().get(key));
        ProductVO firstRead = productService.getProductDetail(TEST_PRODUCT_ID);
        assertEquals(0, OLD_PRICE.compareTo(firstRead.getPrice()));
        assertNotNull(redisTemplate.opsForValue().get(key));

        // 2. UPDATE product -> DELETE product:detail:id.
        UpdateProductDTO update = new UpdateProductDTO();
        update.setPrice(NEW_PRICE);
        update.setVersion(0);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(ignored -> {
            productService.updateProduct(TEST_PRODUCT_ID, update);

            assertNotNull(
                    redisTemplate.opsForValue().get(key),
                    "业务事务提交前不应删除商品详情缓存"
            );
        });

        assertEquals(
                0,
                NEW_PRICE.compareTo(jdbcTemplate.queryForObject(
                        "SELECT price FROM product WHERE id = ?", BigDecimal.class, TEST_PRODUCT_ID))
        );
        assertNull(redisTemplate.opsForValue().get(key));
        assertEquals(
                CacheInvalidationTaskStatus.PENDING.getCode(),
                jdbcTemplate.queryForObject(
                        "SELECT status FROM cache_invalidation_task WHERE cache_key = ?",
                        Integer.class,
                        key
                )
        );

        // 3. A new Redis miss must return MySQL's new price and repopulate Redis.
        ProductVO secondRead = productService.getProductDetail(TEST_PRODUCT_ID);
        assertEquals(0, NEW_PRICE.compareTo(secondRead.getPrice()));
        assertNotNull(redisTemplate.opsForValue().get(key));
    }

    private void insertCategory() {
        Category category = new Category();
        category.setId(TEST_CATEGORY_ID);
        category.setMerchantId(TEST_MERCHANT_ID);
        category.setCategoryName("cache-invalidation-category");
        category.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        category.setIsDefault(CategoryDefaultEnum.CLASSIFICATION.getCode());
        categoryMapper.insert(category);
    }

    private void deleteTestData() {
        redisTemplate.delete(RedisKeyConstant.PRODUCT_DETAIL + TEST_PRODUCT_ID);
        jdbcTemplate.update(
                "DELETE FROM cache_invalidation_task WHERE cache_key = ?",
                RedisKeyConstant.PRODUCT_DETAIL + TEST_PRODUCT_ID
        );
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", TEST_MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM category WHERE merchant_id = ?", TEST_MERCHANT_ID);
        merchantMapper.deleteById(TEST_MERCHANT_ID);
    }
}
