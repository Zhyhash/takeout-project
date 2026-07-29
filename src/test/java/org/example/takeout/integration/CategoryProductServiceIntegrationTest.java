package org.example.takeout.integration;

import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
class CategoryProductServiceIntegrationTest {

    private static final Long TEST_MERCHANT_ID = 9_005_001L;
    private static final Long DEFAULT_CATEGORY_ID = 9_006_001L;
    private static final Long TARGET_CATEGORY_ID = 9_006_002L;
    private static final String TEST_PRODUCT_NAME = "并发测试商品";

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private MerchantMapper merchantMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private void deleteTestData() {
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", TEST_MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM category WHERE merchant_id = ?", TEST_MERCHANT_ID);
        merchantMapper.deleteById(TEST_MERCHANT_ID);
    }
}
