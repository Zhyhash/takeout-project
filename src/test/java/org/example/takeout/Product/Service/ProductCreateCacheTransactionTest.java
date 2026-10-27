package org.example.takeout.Product.Service;

import org.example.takeout.CacheInvalidationTask.Enum.CacheInvalidationTaskStatus;
import org.example.takeout.CacheInvalidationTask.Mapper.CacheInvalidationTaskMapper;
import org.example.takeout.CacheInvalidationTask.Service.CacheInvalidationTaskService;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Common.Redis.RedisCacheClient;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.VO.ProductVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real product/task mappers and Spring transactions; Redis is an in-memory test double. */
@SpringJUnitConfig(ProductCreateCacheTransactionTest.TestConfig.class)
class ProductCreateCacheTransactionTest {

    private static final long MERCHANT_ID = 201L;
    private static final long CATEGORY_ID = 101L;
    private static final long PRODUCT_ID = 301L;
    private static final String CACHE_KEY = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
    private static final String NULL_MARKER = "__NULL__";

    @Autowired private ProductService productService;
    @Autowired private ProductConverter productConverter;
    @Autowired private CategoryService categoryService;
    @Autowired private CacheInvalidationTaskService taskService;
    @Autowired private RedisCacheClient redisCacheClient;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final Map<String, String> redisValues = new HashMap<>();

    @BeforeEach
    void setUp() {
        reset(redisCacheClient, categoryService, productConverter);
        MerchantContextHolder.setMerchantId(MERCHANT_ID);
        redisValues.clear();
        redisValues.put(CACHE_KEY, NULL_MARKER);
        when(redisCacheClient.get(anyString())).thenAnswer(invocation ->
                redisValues.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            redisValues.remove(invocation.getArgument(0, String.class));
            return null;
        }).when(redisCacheClient).delete(anyString());
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(redisCacheClient).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisCacheClient.tryLock(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        Category category = new Category();
        category.setId(CATEGORY_ID);
        category.setCategoryName("主食");
        when(categoryService.lockActiveCategory(CATEGORY_ID, MERCHANT_ID)).thenReturn(category);
        // Fix only the generated ID so the not-yet-created product's negative cache is reproducible.
        ProductConverter delegate = Mappers.getMapper(ProductConverter.class);
        when(productConverter.toProduct(any(CreateProductDTO.class), eq(MERCHANT_ID)))
                .thenAnswer(invocation -> {
                    Product product = delegate.toProduct(invocation.getArgument(0), MERCHANT_ID);
                    product.setId(PRODUCT_ID);
                    return product;
                });
        when(productConverter.toMerchantProductVO(any(), any())).thenAnswer(invocation ->
                delegate.toMerchantProductVO(invocation.getArgument(0), invocation.getArgument(1)));
        when(productConverter.toProductDetailCacheDTO(any())).thenAnswer(invocation ->
                delegate.toProductDetailCacheDTO(invocation.getArgument(0)));
        when(productConverter.toProductVO(any())).thenAnswer(invocation ->
                delegate.toProductVO(invocation.getArgument(0)));

        jdbcTemplate.execute("DROP TABLE IF EXISTS cache_invalidation_task");
        jdbcTemplate.execute("DROP TABLE IF EXISTS product");
        jdbcTemplate.execute("""
                CREATE TABLE product (
                    id BIGINT PRIMARY KEY,
                    category_id BIGINT,
                    product_name VARCHAR(64),
                    image_url VARCHAR(255),
                    price DECIMAL(10, 2),
                    stock INT,
                    stock_version INT NOT NULL DEFAULT 0,
                    merchant_id BIGINT,
                    is_deleted INT,
                    status INT,
                    description VARCHAR(255),
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    version INT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE cache_invalidation_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    cache_key VARCHAR(255) NOT NULL,
                    status TINYINT NOT NULL DEFAULT 0,
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @AfterEach
    void clearContext() {
        MerchantContextHolder.clear();
    }

    @Test
    void createCommitsProductAndTaskBeforeRemovingNegativeCacheAndAllowsDetailRead() {
        assertEquals("商品不存在", assertThrows(BusinessException.class,
                () -> productService.getProductDetail(PRODUCT_ID)).getMessage());

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertEquals(PRODUCT_ID, productService.createProduct(createProduct()).getId());

            assertEquals(1, productCount());
            assertEquals(1, taskCount());
            assertEquals(NULL_MARKER, redisValues.get(CACHE_KEY));
            verify(redisCacheClient, never()).delete(CACHE_KEY);
        });

        verify(redisCacheClient).delete(CACHE_KEY);
        assertEquals(1, productCount());
        assertEquals(1, taskCount());
        assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus());
        assertNull(redisValues.get(CACHE_KEY));

        ProductVO detail = productService.getProductDetail(PRODUCT_ID);
        assertEquals(PRODUCT_ID, detail.getId());
        assertEquals("米饭", detail.getProductName());
        assertEquals(0, BigDecimal.TEN.compareTo(detail.getPrice()));
        assertNotEquals(NULL_MARKER, redisValues.get(CACHE_KEY));
        assertNotNull(redisValues.get(CACHE_KEY));
    }

    @Test
    void outerRollbackRemovesProductAndTaskAndPreservesNegativeCache() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            productService.createProduct(createProduct());
            assertEquals(1, productCount());
            assertEquals(1, taskCount());
            transaction.setRollbackOnly();
        });

        assertEquals(0, productCount());
        assertEquals(0, taskCount());
        assertEquals(NULL_MARKER, redisValues.get(CACHE_KEY));
        verify(redisCacheClient, never()).delete(anyString());
    }

    @Test
    void taskInsertFailureRollsBackProductAndPreservesNegativeCache() {
        jdbcTemplate.execute("ALTER TABLE cache_invalidation_task ADD CONSTRAINT reject_task CHECK (status <> 0)");

        assertThrows(DataAccessException.class, () -> productService.createProduct(createProduct()));

        assertEquals(0, productCount());
        assertEquals(0, taskCount());
        assertEquals(NULL_MARKER, redisValues.get(CACHE_KEY));
        verify(redisCacheClient, never()).delete(anyString());
    }

    @Test
    void failedCommitDeleteKeepsTaskForRetryWhichRemovesNegativeCache() {
        doThrow(new RedisCacheUnavailableException("Redis unavailable", new IllegalStateException()))
                .doAnswer(invocation -> {
                    redisValues.remove(CACHE_KEY);
                    return null;
                }).when(redisCacheClient).delete(CACHE_KEY);

        assertDoesNotThrow(() -> productService.createProduct(createProduct()));

        assertEquals(1, productCount());
        assertEquals(1, taskCount());
        assertEquals(CacheInvalidationTaskStatus.PENDING.getCode(), taskStatus());
        assertEquals(NULL_MARKER, redisValues.get(CACHE_KEY));
        jdbcTemplate.update("UPDATE cache_invalidation_task SET next_retry_time = ?",
                LocalDateTime.now().minusSeconds(1));

        taskService.retryPendingTasks();

        verify(redisCacheClient, times(2)).delete(CACHE_KEY);
        assertEquals(CacheInvalidationTaskStatus.SUCCESS.getCode(), taskStatus());
        assertNull(redisValues.get(CACHE_KEY));
        assertEquals(PRODUCT_ID, productService.getProductDetail(PRODUCT_ID).getId());
    }

    private CreateProductDTO createProduct() {
        CreateProductDTO dto = new CreateProductDTO();
        dto.setCategoryId(CATEGORY_ID);
        dto.setProductName("米饭");
        dto.setPrice(BigDecimal.TEN);
        dto.setStock(5);
        return dto;
    }

    private int productCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class);
    }

    private int taskCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cache_invalidation_task", Integer.class);
    }

    private int taskStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM cache_invalidation_task", Integer.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = {ProductMapper.class, CacheInvalidationTaskMapper.class},
            annotationClass = org.apache.ibatis.annotations.Mapper.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:product-create-cache;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        CategoryService categoryService() {
            return mock(CategoryService.class);
        }

        @Bean
        ProductConverter productConverter() {
            return mock(ProductConverter.class);
        }

        @Bean
        RedisCacheClient redisCacheClient() {
            return mock(RedisCacheClient.class);
        }

        @Bean
        CacheInvalidationTaskService taskService(CacheInvalidationTaskMapper mapper, RedisCacheClient redis) {
            return new CacheInvalidationTaskService(mapper, redis);
        }

        @Bean
        ProductDetailCacheService productDetailCacheService(CacheInvalidationTaskService tasks,
                                                          RedisCacheClient redis) {
            return new ProductDetailCacheService(tasks, redis, new ObjectMapper());
        }

        @Bean
        ProductService productService(ProductMapper mapper, CategoryService categories,
                                      ProductConverter converter, ProductDetailCacheService cache) {
            return new ProductService(mapper, categories, converter, cache);
        }
    }
}
