package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.dataFactory.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductMapper productMapper;

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
        }
    }



    @Test
    void update_shouldFailWhenVersionIsStale(){

        Product product =
                TestDataFactory.createProduct(
                        TEST_PRODUCT_ID,
                        "测试商品",
                        10,
                        TEST_MERCHANT_ID
                );

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
        merchantMapper.deleteById(TEST_MERCHANT_ID);
    }
}



