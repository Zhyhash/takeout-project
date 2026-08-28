package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=jdbc:mysql://localhost:3306/takeout_integration_test?createDatabaseIfNotExist=true&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.sql.init.mode=never",
        "jwt.secret=test-secret-key-at-least-32-characters-long!!",
        "jwt.expire-days=7"
})
public class OptimisticLockIntegrationTest {

    private static final Long TEST_USER_ID = 9_001_001L;
    private static final Long TEST_MERCHANT_ID = 9_002_001L;
    private static final Long TEST_PRODUCT_ID = 9_003_001L;
    private static final Long TEST_CART_ID_CUP = 9_004_001L;
    private static final Long TEST_CART_ID_MILK = 9_004_002L;
    private static final Long TEST_CATEGORY_ID = 9_005_001L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductService productService;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private MerchantMapper merchantMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        deleteTestData();

        UserContextHolder.setUserId(TEST_USER_ID);
    }

    @AfterEach
    void tearDown() {
        try {
            deleteTestData();
        } finally {
            UserContextHolder.clear();
            MerchantContextHolder.clear();
        }
    }



    //NOTE：测试使用过期版本更新商品时乐观锁会拒绝更新并保留最新数据
    @Test
    void update_shouldFailWhenVersionIsStale(){

        Product product =
                TestDataFactory.createProduct(
                        TEST_PRODUCT_ID,
                        "测试商品",
                        10,
                        TEST_MERCHANT_ID
                );
        product.setCategoryId(TEST_CATEGORY_ID);

        insertCategory();
        productMapper.insert(product);


        Product first =
                productMapper.selectById(TEST_PRODUCT_ID);

        Product second =
                productMapper.selectById(TEST_PRODUCT_ID);


        first.setStock(20);

        assertEquals(
                1,
                productMapper.updateById(first)
        );


        second.setStock(30);

        assertEquals(
                0,
                productMapper.updateById(second)
        );


        Product result =
                productMapper.selectById(TEST_PRODUCT_ID);

        assertEquals(20,result.getStock());
        assertEquals(1,result.getVersion());
    }

    @Test
    void merchantUpdate_shouldFailWhenVersionIsStale() {
        merchantMapper.insert(TestDataFactory.createOpenMerchant(TEST_MERCHANT_ID));

        Merchant first = merchantMapper.selectById(TEST_MERCHANT_ID);
        Merchant second = merchantMapper.selectById(TEST_MERCHANT_ID);

        first.setAddress("first concurrent update");
        assertEquals(1, merchantMapper.updateById(first));

        second.setAddress("stale concurrent update");
        assertEquals(0, merchantMapper.updateById(second));

        Merchant result = merchantMapper.selectById(TEST_MERCHANT_ID);
        assertEquals("first concurrent update", result.getAddress());
        assertEquals(1, result.getVersion());
    }

    @Test
    void stockCrossingZeroUpdatesStatusAndVersionAtomically() {
        insertCategory();
        Product product = TestDataFactory.createProduct(
                TEST_PRODUCT_ID, "库存状态流转商品", 1, TEST_MERCHANT_ID);
        product.setCategoryId(TEST_CATEGORY_ID);
        productMapper.insert(product);

        productService.decreaseStock(TEST_PRODUCT_ID, 1);

        Product soldOut = productMapper.selectById(TEST_PRODUCT_ID);
        assertEquals(0, soldOut.getStock());
        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), soldOut.getStatus());
        assertEquals(1, soldOut.getVersion());

        productService.increaseStock(TEST_PRODUCT_ID, 2);

        Product onSale = productMapper.selectById(TEST_PRODUCT_ID);
        assertEquals(2, onSale.getStock());
        assertEquals(ProductStatusEnum.ON_SALE.getCode(), onSale.getStatus());
        assertEquals(2, onSale.getVersion());

        jdbcTemplate.update(
                "UPDATE product SET status = ? WHERE id = ?",
                ProductStatusEnum.OFF_SALE.getCode(), TEST_PRODUCT_ID);
        productService.increaseStock(TEST_PRODUCT_ID, 1);

        Product stillOffSale = productMapper.selectById(TEST_PRODUCT_ID);
        assertEquals(3, stillOffSale.getStock());
        assertEquals(ProductStatusEnum.OFF_SALE.getCode(), stillOffSale.getStatus());
        assertEquals(3, stillOffSale.getVersion());
    }

    @Test
    void returnedStockMakesMerchantAbsoluteResetWithStaleVersionFail() {
        insertCategory();
        Product product = TestDataFactory.createProduct(
                TEST_PRODUCT_ID, "库存重置并发商品", 10, TEST_MERCHANT_ID);
        product.setCategoryId(TEST_CATEGORY_ID);
        productMapper.insert(product);

        UpdateProductDTO staleReset = new UpdateProductDTO();
        staleReset.setStock(20);
        staleReset.setVersion(0);

        productService.increaseStock(TEST_PRODUCT_ID, 1);
        MerchantContextHolder.setMerchantId(TEST_MERCHANT_ID);

        assertThrows(BusinessException.class,
                () -> productService.updateProduct(TEST_PRODUCT_ID, staleReset));

        Product result = productMapper.selectById(TEST_PRODUCT_ID);
        assertEquals(11, result.getStock());
        assertEquals(1, result.getVersion());
    }

    @Test
    void deletedProductReceivesReturnedStockAndRestoresOffSale() {
        insertCategory();
        Product product = TestDataFactory.createProduct(
                TEST_PRODUCT_ID, "删除商品退库测试", 0, TEST_MERCHANT_ID);
        product.setCategoryId(TEST_CATEGORY_ID);
        product.setStatus(ProductStatusEnum.SALE_OUT.getCode());
        productMapper.insert(product);

        assertEquals(1, productMapper.deleteById(TEST_PRODUCT_ID));

        productService.increaseStock(TEST_PRODUCT_ID, 2);

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM product WHERE id = ?", Integer.class, TEST_PRODUCT_ID));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = ?", Integer.class, TEST_PRODUCT_ID));
        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), jdbcTemplate.queryForObject(
                "SELECT status FROM product WHERE id = ?", Integer.class, TEST_PRODUCT_ID));

        MerchantContextHolder.setMerchantId(TEST_MERCHANT_ID);
        productService.restoreProduct(TEST_PRODUCT_ID);

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM product WHERE id = ?", Integer.class, TEST_PRODUCT_ID));
        assertEquals(ProductStatusEnum.OFF_SALE.getCode(), jdbcTemplate.queryForObject(
                "SELECT status FROM product WHERE id = ?", Integer.class, TEST_PRODUCT_ID));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = ?", Integer.class, TEST_PRODUCT_ID));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT version FROM product WHERE id = ?", Integer.class, TEST_PRODUCT_ID));
    }

    private void deleteTestData() {
        List<Long> orderIds = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, TEST_USER_ID))
                .stream()
                .map(Order::getId)
                .toList();
        if (!orderIds.isEmpty()) {
            orderItemMapper.delete(Wrappers.<OrderItem>lambdaQuery()
                    .in(OrderItem::getOrderId, orderIds));
        }
        orderMapper.delete(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, TEST_USER_ID));
        cartMapper.delete(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, TEST_USER_ID));
        // Product 使用逻辑删除；测试清理必须物理删除，才能安全复用固定主键。
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", TEST_MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM category WHERE merchant_id = ?", TEST_MERCHANT_ID);
        merchantMapper.deleteById(TEST_MERCHANT_ID);
    }

    private void insertCategory() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO category (id, merchant_id, category_name, status, is_default)
                VALUES (?, ?, 'optimistic_lock_test_category', 0, 0)
                """, TEST_CATEGORY_ID, TEST_MERCHANT_ID);
    }
}



