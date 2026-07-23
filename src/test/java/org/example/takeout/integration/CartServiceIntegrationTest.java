package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.Service.CartService;
import org.example.takeout.Cart.VO.CartListVO;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
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
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=jdbc:mysql://localhost:3306/takeout_integration_test?createDatabaseIfNotExist=true&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.sql.init.mode=never",
        "jwt.secret=test-secret-key-at-least-32-characters-long!!",
        "jwt.expire-days=7"
})
public class CartServiceIntegrationTest {
    public static final Long MERCHANT_A_ID = 1001L;  // 商家A
    public static final Long MERCHANT_B_ID = 1002L;  // 商家B
    public static final Long CATEGORY_A_ID = 4001L;
    public static final Long CATEGORY_B_ID = 4002L;

    // ==================== 商品常量 ====================
    public static final Long PRODUCT_A1_ID = 2001L;  // 商家A的商品1
    public static final Long PRODUCT_A2_ID = 2002L;  // 商家A的商品2
    public static final Long PRODUCT_B1_ID = 3001L;  // 商家B的商品1
    public static final Long PRODUCT_B2_ID = 3002L;  // 商家B的商品2

    public static final String PRODUCT_A1_NAME = "iPhone 15 Pro";
    public static final String PRODUCT_A2_NAME = "MacBook Air M3";
    public static final String PRODUCT_B1_NAME = "小米14 Ultra";
    public static final String PRODUCT_B2_NAME = "华为Mate 60 Pro";

    // ==================== 用户常量 ====================
    public static final Long USER_1_ID = 5001L;  // 用户1
    public static final Long USER_2_ID = 5002L;  // 用户2

    // ==================== 购物车常量 ====================
    public static final Long CART_ITEM_1_ID = 6001L;
    public static final Long CART_ITEM_2_ID = 6002L;
    public static final Long CART_ITEM_3_ID = 6003L;
    public static final Long CART_ITEM_4_ID = 6004L;

    @Autowired
    CartService cartService;
    @Autowired
    CartMapper cartMapper;
    @Autowired
    ProductMapper  productMapper;
    @Autowired
    private MerchantMapper merchantMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        deleteTestData();

        UserContextHolder.setUserId(USER_1_ID);
    }

    private void deleteTestData() {
        List<Long> orderIds = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, USER_1_ID))
                .stream()
                .map(Order::getId)
                .toList();
        if (!orderIds.isEmpty()) {
            orderItemMapper.delete(Wrappers.<OrderItem>lambdaQuery()
                    .in(OrderItem::getOrderId, orderIds));
        }
        orderMapper.delete(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, USER_1_ID));
        cartMapper.delete(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, USER_1_ID));
        // Product 使用逻辑删除；测试清理必须物理删除，才能安全复用固定主键。
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", MERCHANT_A_ID);
        jdbcTemplate.update("DELETE FROM product WHERE merchant_id = ?", MERCHANT_B_ID);
        categoryMapper.deleteById(CATEGORY_A_ID);
        categoryMapper.deleteById(CATEGORY_B_ID);
        merchantMapper.deleteById(MERCHANT_A_ID);
        merchantMapper.deleteById(MERCHANT_B_ID);
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
    public void shouldIncreaseQuantityWhenSameProductIsAddedTwice(){
        CartTestData cartTestData = createProductsAndSameMerchant();
        AddCartDTO iphoneDTO = cartTestData.iphoneDTO();

        cartService.add(iphoneDTO);
        cartService.add(iphoneDTO);

        List<CartItem> cartItems = cartMapper.selectList(
                new QueryWrapper<CartItem>()
                        .eq("user_id", USER_1_ID)
                        .eq("product_id", PRODUCT_A1_ID)
        );
        assert(cartItems.size() == 1);

        assert(cartItems.get(0).getQuantity().equals(2));
    }

    @Test
    public void shouldHandleConcurrentAddsOfSameProduct() throws InterruptedException {
        CartTestData cartTestData = createProductsAndSameMerchant();
        AddCartDTO iphoneDTO = cartTestData.iphoneDTO();

        CountDownLatch latch = new CountDownLatch(1);  // 创建一个门闩，计数器=1

        Thread t1 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(USER_1_ID);
                latch.await();              // t1 在这里阻塞，等待 latch 打开
                cartService.add(iphoneDTO);  // 门闩打开后，t1 执行添加操作
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                UserContextHolder.clear();
            }

        });

        Thread t2 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(USER_1_ID);
                latch.await();              // t2 也在这里阻塞，等待 latch 打开
                cartService.add(iphoneDTO);  // 门闩打开后，t2 执行添加操作
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                UserContextHolder.clear();
            }

        });

        t1.start();   // 启动 t1，t1 运行到 await() 处阻塞
        t2.start();   // 启动 t2，t2 运行到 await() 处阻塞
        // 此时两个线程都卡在 await，都没执行 add()

        latch.countDown();  // 计数器减到 0，门闩打开！
        // t1 和 t2 同时被唤醒，几乎同时执行 add()

        t1.join();  // 主线程等待 t1 执行完
        t2.join();  // 主线程等待 t2 执行完
        List<CartItem> cartItems = cartMapper.selectList(
                new QueryWrapper<CartItem>()
                        .eq("user_id", USER_1_ID)
                        .eq("product_id", PRODUCT_A1_ID)
        );
        assertEquals(1, cartItems.size());
        assertEquals(2, cartItems.get(0).getQuantity());  // 数量应该是 2，不是 1

        CartListVO list = cartService.list();
        assertEquals(true, list.getCanBuy());
        assertEquals("", list.getInvalidReason());
    }

    @Test
    public void list_shouldDetectMultipleMerchantAfterConcurrentAdd() throws InterruptedException {
        CartTestData cartTestData = createProductsAndDifferentMerchants();
        AddCartDTO iphoneDTO = cartTestData.iphoneDTO();
        AddCartDTO macbookDTO = cartTestData.macbookDTO();

        CountDownLatch latch = new CountDownLatch(1);  // 创建一个门闩，计数器=1

        Thread t1 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(USER_1_ID);
                latch.await();              // t1 在这里阻塞，等待 latch 打开
                cartService.add(iphoneDTO);  // 门闩打开后，t1 执行添加操作
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                UserContextHolder.clear();
            }

        });

        Thread t2 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(USER_1_ID);
                latch.await();              // t2 也在这里阻塞，等待 latch 打开
                cartService.add(macbookDTO);  // 门闩打开后，t2 执行添加操作
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                UserContextHolder.clear();
            }

        });

        t1.start();   // 启动 t1，t1 运行到 await() 处阻塞
        t2.start();   // 启动 t2，t2 运行到 await() 处阻塞
        // 此时两个线程都卡在 await，都没执行 add()

        latch.countDown();  // 计数器减到 0，门闩打开！
        // t1 和 t2 同时被唤醒，几乎同时执行 add()

        t1.join();  // 主线程等待 t1 执行完
        t2.join();  // 主线程等待 t2 执行完
        List<CartItem> cartItems = cartMapper.selectList(
                new QueryWrapper<CartItem>()
                        .eq("user_id", USER_1_ID)
        );
        assertEquals(2, cartItems.size());
        assertEquals(1, cartItems.get(0).getQuantity());
        assertEquals(1, cartItems.get(1).getQuantity());

        CartListVO list = cartService.list();
        assertEquals(false, list.getCanBuy());
        assertTrue(list.getInvalidReason().contains("用户购物车有多商家"));
    }

    @Test
    public void shouldAddOnceConcurrentAddsOfSameProduct() throws InterruptedException {
        createCartAndProductFromSameMerchant();
        AddCartDTO addCartDto = createAddCartDto(PRODUCT_A1_ID);

        CountDownLatch latch = new CountDownLatch(1);  // 创建一个门闩，计数器=1

        Thread t1 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(USER_1_ID);
                latch.await();              // t1 在这里阻塞，等待 latch 打开
                cartService.add(addCartDto);  // 门闩打开后，t1 执行添加操作
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                UserContextHolder.clear();
            }

        });

        Thread t2 = new Thread(() -> {
            try {
                UserContextHolder.setUserId(USER_1_ID);
                latch.await();              // t2 也在这里阻塞，等待 latch 打开
                cartService.add(addCartDto);  // 门闩打开后，t2 执行添加操作
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                UserContextHolder.clear();
            }

        });

        t1.start();   // 启动 t1，t1 运行到 await() 处阻塞
        t2.start();   // 启动 t2，t2 运行到 await() 处阻塞
        // 此时两个线程都卡在 await，都没执行 add()

        latch.countDown();  // 计数器减到 0，门闩打开！
        // t1 和 t2 同时被唤醒，几乎同时执行 add()

        t1.join();  // 主线程等待 t1 执行完
        t2.join();  // 主线程等待 t2 执行完
        CartItem cartItems= cartMapper.selectOne(
                new QueryWrapper<CartItem>()
                        .eq("user_id", USER_1_ID)
        );
        assertEquals(12, cartItems.getQuantity());
    }

    private CartTestData createProductsAndSameMerchant() {
        Product iphone = TestDataFactory.createProduct(PRODUCT_A1_ID, PRODUCT_A1_NAME, 100, MERCHANT_A_ID);
        Product macbook = TestDataFactory.createProduct(PRODUCT_A2_ID, PRODUCT_A2_NAME, 100, MERCHANT_A_ID);
        Merchant merchant = TestDataFactory.createOpenMerchant(MERCHANT_A_ID);
        iphone.setCategoryId(CATEGORY_A_ID);
        macbook.setCategoryId(CATEGORY_A_ID);

        merchantMapper.insert(merchant);
        categoryMapper.insert(createCategory(CATEGORY_A_ID, MERCHANT_A_ID));
        productMapper.insert(iphone);
        productMapper.insert(macbook);

        return new CartTestData(
                createAddCartDto(PRODUCT_A1_ID),
                createAddCartDto(PRODUCT_A2_ID)
        );
    }

    private CartTestData createProductsAndDifferentMerchants() {
        Product iphone = TestDataFactory.createProduct(PRODUCT_A1_ID, PRODUCT_A1_NAME, 100, MERCHANT_A_ID);
        Product macbook = TestDataFactory.createProduct(PRODUCT_B1_ID, PRODUCT_B1_NAME, 100, MERCHANT_B_ID);
        Merchant merchant1 = TestDataFactory.createOpenMerchant(MERCHANT_A_ID);
        Merchant merchant2 = TestDataFactory.createOpenMerchant(MERCHANT_B_ID);
        iphone.setCategoryId(CATEGORY_A_ID);
        macbook.setCategoryId(CATEGORY_B_ID);

        merchantMapper.insert(merchant1);
        merchantMapper.insert(merchant2);
        categoryMapper.insert(createCategory(CATEGORY_A_ID, MERCHANT_A_ID));
        categoryMapper.insert(createCategory(CATEGORY_B_ID, MERCHANT_B_ID));
        productMapper.insert(iphone);
        productMapper.insert(macbook);

        return new CartTestData(
                createAddCartDto(PRODUCT_A1_ID),
                createAddCartDto(PRODUCT_B1_ID)
        );
    }

    private AddCartDTO createAddCartDto(Long productId) {
        AddCartDTO addCartDTO = new AddCartDTO();
        addCartDTO.setProductId(productId);
        return addCartDTO;
    }

    private void createCartAndProductFromSameMerchant() {
        Product iphone = TestDataFactory.createProduct(PRODUCT_A1_ID, PRODUCT_A1_NAME, 100, MERCHANT_A_ID);
        CartItem cartItem = TestDataFactory.createCartItem(CART_ITEM_1_ID, USER_1_ID, iphone, 10);
        Merchant openMerchant = TestDataFactory.createOpenMerchant(MERCHANT_A_ID);
        iphone.setCategoryId(CATEGORY_A_ID);
        merchantMapper.insert(openMerchant);
        categoryMapper.insert(createCategory(CATEGORY_A_ID, MERCHANT_A_ID));
        productMapper.insert(iphone);
        cartMapper.insert(cartItem);
    }

    private Category createCategory(Long id, Long merchantId) {
        Category category = new Category();
        category.setId(id);
        category.setMerchantId(merchantId);
        category.setCategoryName("购物车集成测试分类");
        category.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        category.setIsDefault(CategoryDefaultEnum.DEFAULT.getCode());
        return category;
    }

    private record CartTestData(AddCartDTO iphoneDTO, AddCartDTO macbookDTO) {
    }

}
