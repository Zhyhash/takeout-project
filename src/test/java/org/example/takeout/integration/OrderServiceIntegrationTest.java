package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
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
class OrderServiceIntegrationTest {

    private static final Long TEST_USER_ID = 9_001_001L;
    private static final Long TEST_MERCHANT_ID = 9_002_001L;
    private static final Long TEST_PRODUCT_ID_CUP = 9_003_001L;
    private static final Long TEST_PRODUCT_ID_MILK = 9_003_002L;
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
    void cancelOrder_shouldCancelOrderAndRestoreStock() {
        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Order order = insertOrder(OrderStatusEnum.WAIT_PAY.getCode());
        OrderItem item = TestDataFactory.createOrderItem(order.getId(), product, 1);
        orderItemMapper.insert(item);
        int stockBeforeCancellation = productMapper.selectById(product.getId()).getStock();

        orderService.cancelOrder(order.getId());

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(order.getId()).getStatus());
        assertEquals(
                stockBeforeCancellation + item.getQuantity(),
                productMapper.selectById(product.getId()).getStock()
        );
    }

    @Test
    void cancelOrder_shouldFailWhenOrderAlreadyCancelled() {
        insertMerchant();

        Order order = insertOrder(OrderStatusEnum.CANCELLED.getCode());

        assertThrows(BusinessException.class, () -> orderService.cancelOrder(order.getId()));

        assertEquals(OrderStatusEnum.CANCELLED.getCode(), orderMapper.selectById(order.getId()).getStatus());
    }

    @Test
    void createOrder_shouldFailWhenStockInsufficient() {
        insertMerchant();

        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "极简智能水杯", 10);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, product, 15));

        assertThrows(
                BusinessException.class,
                () -> orderService.createOrder(TestDataFactory.createOrderDTO())
        );

        assertEquals(10, productMapper.selectById(product.getId()).getStock());
        assertEquals(1L, countTestUserCartItems());
        assertNull(findTestUserOrder());
    }

    @Test
    void createOrder_shouldFailWhenCartEmpty() {
        insertMerchant();

        insertProduct(TEST_PRODUCT_ID_CUP, "极简智能水杯", 10);

        assertThrows(
                BusinessException.class,
                () -> orderService.createOrder(TestDataFactory.createOrderDTO())
        );

        assertNull(findTestUserOrder());
    }

    @Test
    void createOrder_shouldRollbackAllWhenSecondProductStockInsufficient() {
        insertMerchant();
        Product cup = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        Product milk = insertProduct(TEST_PRODUCT_ID_MILK, "牛奶", 10);
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, cup, 5));
        cartMapper.insert(TestDataFactory.createCartItem(TEST_CART_ID_MILK, TEST_USER_ID, milk, 15));
        long orderItemCountBeforeCreation = orderItemMapper.selectCount(null);

        assertThrows(
                BusinessException.class,
                () -> orderService.createOrder(TestDataFactory.createOrderDTO())
        );

        assertEquals(orderItemCountBeforeCreation, orderItemMapper.selectCount(null));
        assertEquals(10, productMapper.selectById(cup.getId()).getStock());
        assertEquals(10, productMapper.selectById(milk.getId()).getStock());
        assertEquals(2L, countTestUserCartItems());
        assertNull(findTestUserOrder());
    }


    @Test
    void createOrder_shouldCreateSuccessfully(){
        //准备数据
        insertMerchant();
        Product product = insertProduct(TEST_PRODUCT_ID_CUP, "水杯", 10);
        CartItem cartItem = insertCartItem(product, 5);

        orderService.createOrder(TestDataFactory.createOrderDTO());

        Order order = findTestUserOrder();

        assertNotNull(order);
        assertEquals(
                OrderStatusEnum.WAIT_PAY.getCode(),
                order.getStatus()
        );
    }

    private Product insertProduct(Long id, String name, int stock) {
        Product product = TestDataFactory.createProduct(id, name, stock, TEST_MERCHANT_ID);
        productMapper.insert(product);
        return product;
    }

    private Order insertOrder(Integer status) {
        Order order = TestDataFactory.createOrder(TEST_USER_ID, TEST_MERCHANT_ID, status);
        orderMapper.insert(order);
        return order;
    }

    private  CartItem insertCartItem(Product product, Integer quantity) {
        CartItem cartItem = TestDataFactory.createCartItem(TEST_CART_ID_CUP, TEST_USER_ID, product, quantity);
        cartMapper.insert(cartItem);
        return cartItem;
    }

    private Merchant insertMerchant() {
        Merchant openMerchant = TestDataFactory.createOpenMerchant(TEST_MERCHANT_ID);
        merchantMapper.insert(openMerchant);
        return openMerchant;
    }

    private long countTestUserCartItems() {
        return cartMapper.selectCount(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, TEST_USER_ID));
    }

    private Order findTestUserOrder() {
        return orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, TEST_USER_ID));
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
