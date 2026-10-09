package org.example.takeout.Cart.Service;

import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.DTO.DeleteDTO;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.VO.CartVO;
import org.example.takeout.CartHeader.Manager.CartHeaderManager;
import org.example.takeout.CartHeader.Mapper.CartHeaderMapper;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Merchant.Service.MerchantQueryService;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Service.ProductQueryService;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(CartHeaderServiceRegressionTest.TestConfig.class)
class CartHeaderServiceRegressionTest {

    private static final long USER_ID = 101L;
    private static final long OTHER_USER_ID = 202L;
    private static final long MERCHANT_A_ID = 1001L;
    private static final long MERCHANT_B_ID = 1002L;
    private static final long ITEM_A_ID = 10001L;
    private static final long ITEM_A2_ID = 10002L;
    private static final long OTHER_ITEM_ID = 10003L;

    private final Product productA = product(2001L, MERCHANT_A_ID);
    private final Product productA2 = product(2002L, MERCHANT_A_ID);
    private final Product productB = product(3001L, MERCHANT_B_ID);

    // Product validation is outside this test's scope. Each caller receives its
    // own product fixture, including the two concurrent add requests.
    private final ThreadLocal<Product> requestedProduct = new ThreadLocal<>();

    @Autowired
    private CartService cartService;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private CartHeaderMapper cartHeaderMapper;

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private MerchantQueryService merchantQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetCart() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cart (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    merchant_id BIGINT NOT NULL,
                    quantity INT NOT NULL,
                    product_name VARCHAR(255) NOT NULL,
                    product_image VARCHAR(255) NOT NULL,
                    price DECIMAL(10, 2) NOT NULL,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    version INT NOT NULL DEFAULT 0,
                    UNIQUE (user_id, product_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cart_header (
                    user_id BIGINT PRIMARY KEY,
                    merchant_id BIGINT
                )
                """);
        jdbcTemplate.update("DELETE FROM cart");
        jdbcTemplate.update("DELETE FROM cart_header");
        jdbcTemplate.update("INSERT INTO cart_header (user_id) VALUES (?), (?)",
                USER_ID, OTHER_USER_ID);

        reset(productQueryService, merchantQueryService);
        when(productQueryService.findOnSaleProduct(any()))
                .thenAnswer(invocation -> requestedProduct.get());
        when(merchantQueryService.checkMerchantOpen(MERCHANT_A_ID)).thenReturn(false);
        when(merchantQueryService.checkMerchantOpen(MERCHANT_B_ID)).thenReturn(false);
        UserContextHolder.setUserId(USER_ID);
    }

    @AfterEach
    void clearThreadContext() {
        requestedProduct.remove();
        UserContextHolder.clear();
    }

    @Test
    void deletingLastItemUnbindsMerchantAndAllowsAnotherStore() {
        seedCart(ITEM_A_ID, USER_ID, productA, 1);

        cartService.delete(new DeleteDTO(List.of(ITEM_A_ID)));

        assertFalse(cartMapper.existsByUserId(USER_ID));
        assertNull(cartHeaderMapper.selectById(USER_ID).getMerchantId());

        CartVO added = add(productB);

        assertNotNull(added.getId());
        assertEquals(productB.getId(), added.getProductId());
        assertEquals(1, added.getQuantity());
        assertEquals(MERCHANT_B_ID, cartHeaderMapper.selectById(USER_ID).getMerchantId());
        assertEquals(1, itemCount(USER_ID));
    }

    @Test
    void deletingSomeItemsKeepsMerchantBindingAndRejectsAnotherStore() {
        seedCart(ITEM_A_ID, USER_ID, productA, 1);
        seedCart(ITEM_A2_ID, USER_ID, productA2, 2);

        cartService.delete(new DeleteDTO(List.of(ITEM_A_ID)));

        assertNull(cartMapper.selectById(ITEM_A_ID));
        assertEquals(2, cartMapper.selectById(ITEM_A2_ID).getQuantity());
        assertEquals(MERCHANT_A_ID, cartHeaderMapper.selectById(USER_ID).getMerchantId());
        BusinessException rejected = assertThrows(BusinessException.class, () -> add(productB));
        assertEquals("只能加入同一家店的商品", rejected.getMessage());
        assertEquals(1, itemCount(USER_ID));
        assertEquals(MERCHANT_A_ID, cartHeaderMapper.selectById(USER_ID).getMerchantId());
    }

    @Test
    void deletingAnotherUsersItemRollsBackAlreadyDeletedOwnedItem() {
        seedCart(ITEM_A_ID, USER_ID, productA, 2);
        seedCart(OTHER_ITEM_ID, OTHER_USER_ID, productB, 3);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> cartService.delete(new DeleteDTO(List.of(ITEM_A_ID, OTHER_ITEM_ID))));

        assertEquals("购物车清理失败", failure.getMessage());
        assertEquals(2, cartMapper.selectById(ITEM_A_ID).getQuantity());
        assertEquals(3, cartMapper.selectById(OTHER_ITEM_ID).getQuantity());
        assertEquals(MERCHANT_A_ID, cartHeaderMapper.selectById(USER_ID).getMerchantId());
        assertEquals(MERCHANT_B_ID, cartHeaderMapper.selectById(OTHER_USER_ID).getMerchantId());
    }

    @Test
    void repeatedClearKeepsCartEmptyAndDoesNotTouchAnotherUser() {
        seedCart(ITEM_A_ID, USER_ID, productA, 2);
        seedCart(OTHER_ITEM_ID, OTHER_USER_ID, productB, 3);

        cartService.clear();
        assertDoesNotThrow(cartService::clear);

        assertFalse(cartMapper.existsByUserId(USER_ID));
        assertNull(cartHeaderMapper.selectById(USER_ID).getMerchantId());
        assertEquals(3, cartMapper.selectById(OTHER_ITEM_ID).getQuantity());
        assertEquals(MERCHANT_B_ID, cartHeaderMapper.selectById(OTHER_USER_ID).getMerchantId());
    }

    @Test
    void concurrentAddsFromDifferentStoresToEmptyCartAllowOnlyOneStore() throws Exception {
        CyclicBarrier bothRequestsValidated = new CyclicBarrier(2);
        when(productQueryService.findOnSaleProduct(any())).thenAnswer(invocation -> {
            bothRequestsValidated.await(5, TimeUnit.SECONDS);
            return requestedProduct.get();
        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> addedA = workers.submit(() -> addAsUser(productA));
            Future<Boolean> addedB = workers.submit(() -> addAsUser(productB));

            boolean successA = addedA.get(10, TimeUnit.SECONDS);
            boolean successB = addedB.get(10, TimeUnit.SECONDS);

            assertTrue(successA ^ successB, "Exactly one merchant must acquire the empty cart");
            long winningMerchantId = successA ? MERCHANT_A_ID : MERCHANT_B_ID;
            long winningProductId = successA ? productA.getId() : productB.getId();
            assertEquals(winningMerchantId, cartHeaderMapper.selectById(USER_ID).getMerchantId());
            assertEquals(1, itemCount(USER_ID));
            assertEquals(winningProductId, jdbcTemplate.queryForObject(
                    "SELECT product_id FROM cart WHERE user_id = ?", Long.class, USER_ID));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT quantity FROM cart WHERE user_id = ?", Integer.class, USER_ID));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Cart workers must finish");
        }
    }

    private boolean addAsUser(Product product) {
        UserContextHolder.setUserId(USER_ID);
        try {
            add(product);
            return true;
        } catch (BusinessException rejected) {
            assertEquals("只能加入同一家店的商品", rejected.getMessage());
            return false;
        } finally {
            UserContextHolder.clear();
        }
    }

    private CartVO add(Product product) {
        AddCartDTO request = new AddCartDTO();
        request.setProductId(product.getId());
        requestedProduct.set(product);
        try {
            return cartService.add(request);
        } finally {
            requestedProduct.remove();
        }
    }

    private void seedCart(long itemId, long userId, Product product, int quantity) {
        CartItem item = new CartItem();
        item.setId(itemId);
        item.setUserId(userId);
        item.setProductId(product.getId());
        item.setMerchantId(product.getMerchantId());
        item.setProductName(product.getProductName());
        item.setProductImage(product.getImageUrl());
        item.setPrice(product.getPrice());
        item.setQuantity(quantity);
        item.setVersion(0);
        assertEquals(1, cartMapper.insert(item));
        jdbcTemplate.update("UPDATE cart_header SET merchant_id = ? WHERE user_id = ?",
                product.getMerchantId(), userId);
    }

    private int itemCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart WHERE user_id = ?", Integer.class, userId);
    }

    private static Product product(long productId, long merchantId) {
        Product product = new Product();
        product.setId(productId);
        product.setMerchantId(merchantId);
        product.setProductName("Product " + productId);
        product.setImageUrl("/images/" + productId + ".png");
        product.setPrice(new BigDecimal("12.50"));
        product.setStock(100);
        product.setStatus(ProductStatusEnum.ON_SALE.getCode());
        product.setIsDeleted(DeleteConstant.NOT_DELETED);
        return product;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({MybatisPlusConfig.class, CartService.class, CartHeaderManager.class})
    @MapperScan(basePackageClasses = {CartMapper.class, CartHeaderMapper.class})
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:cart-header-service-regression;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                    "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ProductQueryService productQueryService() {
            return mock(ProductQueryService.class);
        }

        @Bean
        MerchantQueryService merchantQueryService() {
            return mock(MerchantQueryService.class);
        }
    }
}
