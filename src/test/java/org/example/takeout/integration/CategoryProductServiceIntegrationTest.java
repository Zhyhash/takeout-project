package org.example.takeout.integration;

import lombok.RequiredArgsConstructor;
import org.example.takeout.CacheInvalidationTask.Config.CacheInvalidationTaskScheduler;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=jdbc:mysql://localhost:3306/takeout_integration_test?createDatabaseIfNotExist=true&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.sql.init.mode=never",
        "jwt.secret=test-secret-key-at-least-32-characters-long!!",
        "jwt.expire-days=7"
})
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class CategoryProductServiceIntegrationTest {

    private static final Long TEST_MERCHANT_ID = 9_005_001L;
    private static final Long DEFAULT_CATEGORY_ID = 9_006_001L;
    private static final Long TARGET_CATEGORY_ID = 9_006_002L;
    private static final String TEST_PRODUCT_NAME = "并发测试商品";

    private final CategoryService categoryService;

    private final ProductService productService;

    private final ProductMapper productMapper;

    private final CategoryMapper categoryMapper;

    private final MerchantMapper merchantMapper;

    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CacheInvalidationTaskScheduler cacheInvalidationTaskScheduler;

    @BeforeEach
    void setUp() {
        deleteTestData();
        merchantMapper.insert(TestDataFactory.createOpenMerchant(TEST_MERCHANT_ID));
        insertCategory(DEFAULT_CATEGORY_ID, "默认分类", CategoryDefaultEnum.DEFAULT.getCode());
        insertCategory(TARGET_CATEGORY_ID, "待删除分类", CategoryDefaultEnum.CLASSIFICATION.getCode());
        MerchantContextHolder.setMerchantId(TEST_MERCHANT_ID);
    }

    @AfterEach
    void tearDown() {
        try {
            deleteTestData();
        } finally {
            MerchantContextHolder.clear();
        }
    }

    @Test
    void createProduct_shouldUseDefaultImageWhenImageUrlMissing() {
        MerchantProductVO nullImageProduct = productService.createProduct(
                createProductDTO("default_null", null));
        MerchantProductVO blankImageProduct = productService.createProduct(
                createProductDTO("default_blank", "   "));

        assertEquals(ProductService.DEFAULT_PRODUCT_IMAGE_URL, nullImageProduct.getImageUrl());
        assertEquals(ProductService.DEFAULT_PRODUCT_IMAGE_URL, blankImageProduct.getImageUrl());
        assertEquals(ProductService.DEFAULT_PRODUCT_IMAGE_URL, productImageUrl("default_null"));
        assertEquals(ProductService.DEFAULT_PRODUCT_IMAGE_URL, productImageUrl("default_blank"));
    }

    @Test
    void createProductCanReuseDeletedNameAcrossMultipleLifecycles() {
        MerchantProductVO first = productService.createProduct(
                createProductDTO("可乐", "https://example.test/cola-1.png"));
        assertEquals(1, productMapper.deleteById(first.getId()));

        MerchantProductVO second = productService.createProduct(
                createProductDTO("可乐", "https://example.test/cola-2.png"));
        assertEquals(1, productMapper.deleteById(second.getId()));

        MerchantProductVO third = productService.createProduct(
                createProductDTO("可乐", "https://example.test/cola-3.png"));

        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(second.getId(), third.getId());
        assertEquals(3, productCountIncludingDeleted("可乐"));
        assertEquals(1, activeProductCount("可乐"));
        assertEquals(third.getId(), activeProductId("可乐"));
    }

    @Test
    void createProductRejectsDuplicateActiveName() {
        productService.createProduct(
                createProductDTO("雪碧", "https://example.test/sprite-1.png"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.createProduct(
                        createProductDTO("雪碧", "https://example.test/sprite-2.png"))
        );

        assertEquals("当前店铺已存在同名商品", exception.getMessage());
        assertEquals(1, productCountIncludingDeleted("雪碧"));
        assertEquals(1, activeProductCount("雪碧"));
    }

    @Test
    void restoreProductReactivatesOriginalRecordAndOccupiesActiveName() {
        MerchantProductVO deleted = productService.createProduct(
                createProductDTO("芬达", "https://example.test/fanta.png"));
        assertEquals(1, productMapper.deleteById(deleted.getId()));

        productService.restoreProduct(deleted.getId());

        assertEquals(0, productDeletedFlag(deleted.getId()));
        assertEquals(ProductStatusEnum.OFF_SALE.getCode(), productStatus(deleted.getId()));
        assertEquals(deleted.getId(), activeProductId("芬达"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.createProduct(
                        createProductDTO("芬达", "https://example.test/fanta-new.png"))
        );
        assertEquals("当前店铺已存在同名商品", exception.getMessage());
    }

    @Test
    void restoreProductRejectsNameUsedByNewActiveProduct() {
        MerchantProductVO deleted = productService.createProduct(
                createProductDTO("橙汁", "https://example.test/orange-old.png"));
        assertEquals(1, productMapper.deleteById(deleted.getId()));
        MerchantProductVO active = productService.createProduct(
                createProductDTO("橙汁", "https://example.test/orange-new.png"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.restoreProduct(deleted.getId())
        );

        assertEquals("当前店铺已存在同名商品", exception.getMessage());
        assertEquals(1, productDeletedFlag(deleted.getId()));
        assertEquals(active.getId(), activeProductId("橙汁"));
        assertEquals(1, activeProductCount("橙汁"));
    }

    @Test
    void concurrentRestoreOfDeletedSameNameAllowsExactlyOneWinner()
            throws InterruptedException {
        MerchantProductVO first = productService.createProduct(
                createProductDTO("柠檬茶", "https://example.test/lemon-1.png"));
        assertEquals(1, productMapper.deleteById(first.getId()));
        MerchantProductVO second = productService.createProduct(
                createProductDTO("柠檬茶", "https://example.test/lemon-2.png"));
        assertEquals(1, productMapper.deleteById(second.getId()));

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread firstRestore = restoreThread(
                "restore-first-product",
                first.getId(),
                readyLatch,
                startLatch,
                firstFailure
        );
        Thread secondRestore = restoreThread(
                "restore-second-product",
                second.getId(),
                readyLatch,
                startLatch,
                secondFailure
        );

        firstRestore.start();
        secondRestore.start();
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "恢复线程未在规定时间内准备就绪");
        startLatch.countDown();
        firstRestore.join(10_000);
        secondRestore.join(10_000);

        assertFalse(firstRestore.isAlive(), "第一个恢复线程未在规定时间内结束");
        assertFalse(secondRestore.isAlive(), "第二个恢复线程未在规定时间内结束");

        int successCount = 0;
        int conflictCount = 0;
        for (Throwable failure : new Throwable[]{firstFailure.get(), secondFailure.get()}) {
            if (failure == null) {
                successCount++;
                continue;
            }
            BusinessException conflict = assertInstanceOf(BusinessException.class, failure);
            assertEquals("当前店铺已存在同名商品", conflict.getMessage());
            conflictCount++;
        }

        assertEquals(1, successCount);
        assertEquals(1, conflictCount);
        assertEquals(2, productCountIncludingDeleted("柠檬茶"));
        assertEquals(1, activeProductCount("柠檬茶"));
    }



    //NOTE：测试连续一百次执行商品创建与分类删除的并发场景时不会产生孤儿商品
    @RepeatedTest(value = 100, name = "第 {currentRepetition} 次 / 共 {totalRepetitions} 次")
    void testMyMethod(RepetitionInfo repetitionInfo) {
        int current = repetitionInfo.getCurrentRepetition();

        try {
            // 直接调用，如果抛出异常，JUnit 会自动标记为失败
            shouldNotLeaveOrphanProductWhenCreatingProductAndDeletingCategoryConcurrently();

            // 每10次打印进度（可选）
            if (current % 10 == 0) {
                System.out.println("✅ 已完成: " + current + "/100");
            }
        } catch (Exception e) {
            // 如果抛异常，用 Assertions.fail() 明确标记失败并附带详细信息
            Assertions.fail("第 " + current + " 次执行失败！异常信息: " + e.getMessage(), e);
        }
    }

    //NOTE：测试商品创建与所属分类删除并发执行时不会产生孤儿商品
    @Test
    void shouldNotLeaveOrphanProductWhenCreatingProductAndDeletingCategoryConcurrently()
            throws InterruptedException {
        CreateProductDTO createProductDTO = createProductDTO();
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Throwable> createFailure = new AtomicReference<>();
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();

        Thread createThread = new Thread(() -> {
            MerchantContextHolder.setMerchantId(TEST_MERCHANT_ID);
            try {
                readyLatch.countDown();
                startLatch.await();
                productService.createProduct(createProductDTO);
            } catch (Throwable throwable) {
                createFailure.set(throwable);
            } finally {
                MerchantContextHolder.clear();
            }
        }, "create-product-thread");

        Thread deleteThread = new Thread(() -> {
            MerchantContextHolder.setMerchantId(TEST_MERCHANT_ID);
            try {
                readyLatch.countDown();
                startLatch.await();
                categoryService.deleteById(TARGET_CATEGORY_ID);
            } catch (Throwable throwable) {
                deleteFailure.set(throwable);
            } finally {
                MerchantContextHolder.clear();
            }
        }, "delete-category-thread");

        createThread.start();
        deleteThread.start();

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "并发线程未在规定时间内准备就绪");
        startLatch.countDown();

        createThread.join(10_000);
        deleteThread.join(10_000);

        assertFalse(createThread.isAlive(), "创建商品线程未在规定时间内结束");
        assertFalse(deleteThread.isAlive(), "删除分类线程未在规定时间内结束");
        assertNull(deleteFailure.get(), "删除分类不应失败");
        assertTrue(
                createFailure.get() == null || createFailure.get() instanceof BusinessException,
                () -> "创建商品出现非预期异常: " + createFailure.get()
        );

        Integer deletedCategoryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE id = ?",
                Integer.class,
                TARGET_CATEGORY_ID
        );
        Integer orphanProductCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM product p
                LEFT JOIN category c ON c.id = p.category_id
                WHERE p.merchant_id = ?
                  AND p.is_deleted = 0
                  AND c.id IS NULL
                """,
                Integer.class,
                TEST_MERCHANT_ID
        );

        assertEquals(0, deletedCategoryCount, "目标分类最终应被删除");
        assertEquals(0, orphanProductCount, "最终不应存在指向已删除分类的孤儿商品");
    }

    private void insertCategory(Long id, String name, Integer isDefault) {
        Category category = new Category();
        category.setId(id);
        category.setMerchantId(TEST_MERCHANT_ID);
        category.setCategoryName(name);
        category.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        category.setIsDefault(isDefault);
        categoryMapper.insert(category);
    }

    private CreateProductDTO createProductDTO() {
        CreateProductDTO dto = new CreateProductDTO();
        dto.setProductName(TEST_PRODUCT_NAME);
        dto.setDescription("分类删除与商品创建并发冲突测试");
        dto.setPrice(new BigDecimal("18.80"));
        dto.setStock(20);
        dto.setImageUrl("https://example.test/concurrent-product.png");
        dto.setCategoryId(TARGET_CATEGORY_ID);
        return dto;
    }

    private CreateProductDTO createProductDTO(String productName, String imageUrl) {
        CreateProductDTO dto = createProductDTO();
        dto.setProductName(productName);
        dto.setImageUrl(imageUrl);
        return dto;
    }

    private String productImageUrl(String productName) {
        return jdbcTemplate.queryForObject(
                "SELECT image_url FROM product WHERE merchant_id = ? AND product_name = ?",
                String.class,
                TEST_MERCHANT_ID,
                productName
        );
    }

    private int productCountIncludingDeleted(String productName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE merchant_id = ? AND product_name = ?",
                Integer.class,
                TEST_MERCHANT_ID,
                productName
        );
    }

    private int activeProductCount(String productName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM product
                WHERE merchant_id = ?
                  AND product_name = ?
                  AND is_deleted = 0
                """,
                Integer.class,
                TEST_MERCHANT_ID,
                productName
        );
    }

    private Long activeProductId(String productName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM product
                WHERE merchant_id = ?
                  AND product_name = ?
                  AND is_deleted = 0
                """,
                Long.class,
                TEST_MERCHANT_ID,
                productName
        );
    }

    private int productDeletedFlag(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM product WHERE id = ?",
                Integer.class,
                productId
        );
    }

    private int productStatus(Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM product WHERE id = ?",
                Integer.class,
                productId
        );
    }

    private Thread restoreThread(String name,
                                 Long productId,
                                 CountDownLatch readyLatch,
                                 CountDownLatch startLatch,
                                 AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            MerchantContextHolder.setMerchantId(TEST_MERCHANT_ID);
            try {
                readyLatch.countDown();
                startLatch.await();
                productService.restoreProduct(productId);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                MerchantContextHolder.clear();
            }
        }, name);
    }

    private void deleteTestData() {
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", TEST_MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM category WHERE merchant_id = ?", TEST_MERCHANT_ID);
        merchantMapper.deleteById(TEST_MERCHANT_ID);
    }
}
