package org.example.takeout.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.DTO.UpdateCartDTO;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.Service.CartService;
import org.example.takeout.Cart.VO.CartListVO;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
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
import org.example.takeout.testsupport.ConcurrentTestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
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
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CartServiceIntegrationTest {
    public static final Long MERCHANT_A_ID = 1001L;
    public static final Long MERCHANT_B_ID = 1002L;
    public static final Long CATEGORY_A_ID = 4001L;
    public static final Long CATEGORY_B_ID = 4002L;
    public static final Long PRODUCT_A1_ID = 2001L;
    public static final Long PRODUCT_A2_ID = 2002L;
    public static final Long PRODUCT_B1_ID = 3001L;
    public static final Long PRODUCT_B2_ID = 3002L;
    public static final String PRODUCT_A1_NAME = "iPhone 15 Pro";
    public static final String PRODUCT_A2_NAME = "MacBook Air M3";
    public static final String PRODUCT_B1_NAME = "小米14 Ultra";
    public static final String PRODUCT_B2_NAME = "华为Mate 60 Pro";
    public static final Long USER_1_ID = 5001L;
    public static final Long USER_2_ID = 5002L;
    public static final Long CART_ITEM_1_ID = 6001L;
    public static final Long CART_ITEM_2_ID = 6002L;
    public static final Long CART_ITEM_3_ID = 6003L;
    public static final Long CART_ITEM_4_ID = 6004L;

    private final CartService cartService;
    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final CategoryMapper categoryMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        CartHeaderTestFixture.ensureTable(jdbcTemplate);
        deleteTestData();
        CartHeaderTestFixture.insertEmpty(jdbcTemplate, USER_1_ID);
        CartHeaderTestFixture.insertEmpty(jdbcTemplate, USER_2_ID);
        UserContextHolder.setUserId(USER_1_ID);
    }

    private void deleteTestData() {
        List<Long> orderIds = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, USER_1_ID))
                .stream().map(Order::getId).toList();
        if (!orderIds.isEmpty()) {
            orderItemMapper.delete(Wrappers.<OrderItem>lambdaQuery()
                    .in(OrderItem::getOrderId, orderIds));
        }
        orderMapper.delete(Wrappers.<Order>lambdaQuery().eq(Order::getUserId, USER_1_ID));
        cartMapper.delete(Wrappers.<CartItem>lambdaQuery()
                .in(CartItem::getUserId, USER_1_ID, USER_2_ID));
        jdbcTemplate.update("DELETE FROM cart_header WHERE user_id IN (?, ?)", USER_1_ID, USER_2_ID);
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
    public void shouldIncreaseQuantityWhenSameProductIsAddedTwice() {
        AddCartDTO dto = createProductsAndSameMerchant().iphoneDTO();

        cartService.add(dto);
        cartService.add(dto);

        List<CartItem> items = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, USER_1_ID));
        assertEquals(1, items.size());
        assertEquals(2, items.get(0).getQuantity());
        assertEquals(MERCHANT_A_ID, CartHeaderTestFixture.merchantId(jdbcTemplate, USER_1_ID));
    }

    @Test
    public void shouldHandleConcurrentAddsOfSameProduct() {
        AddCartDTO dto = createProductsAndSameMerchant().iphoneDTO();

        addConcurrently(dto);

        List<CartItem> items = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, USER_1_ID));
        assertEquals(1, items.size());
        assertEquals(2, items.get(0).getQuantity());
        assertEquals(MERCHANT_A_ID, CartHeaderTestFixture.merchantId(jdbcTemplate, USER_1_ID));
        CartListVO list = cartService.list();
        assertTrue(list.getCanBuy());
        assertEquals("", list.getInvalidReason());
    }

    @Test
    public void concurrentAddsFromDifferentMerchantsAllowOnlyOneMerchant() {
        CartTestData data = createProductsAndDifferentMerchants();

        ConcurrentTestTemplate.TwoTaskResult<BusinessException, BusinessException> attempts =
                ConcurrentTestTemplate.runTwoTasks(Duration.ofSeconds(10),
                        () -> addAttempt(data.iphoneDTO()), () -> addAttempt(data.macbookDTO()));

        assertNotEquals(attempts.firstResult() == null, attempts.secondResult() == null,
                "并发添加不同商家的商品必须只有一个请求成功");
        BusinessException failure = attempts.firstResult() == null
                ? attempts.secondResult() : attempts.firstResult();
        assertNotNull(failure);
        assertEquals(ResultCodeEnum.BUSINESS_ERROR, failure.getCodeEnum());
        assertEquals("只能加入同一家店的商品", failure.getMessage());
        List<CartItem> items = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, USER_1_ID));
        assertEquals(1, items.size());
        assertEquals(1, items.get(0).getQuantity());
        assertEquals(items.get(0).getMerchantId(), CartHeaderTestFixture.merchantId(jdbcTemplate, USER_1_ID));
        CartListVO list = cartService.list();
        assertTrue(list.getCanBuy());
        assertEquals("", list.getInvalidReason());
    }

    @Test
    public void shouldAddOnceConcurrentAddsOfSameProduct() {
        createCartAndProductFromSameMerchant();

        addConcurrently(createAddCartDto(PRODUCT_A1_ID));

        CartItem item = cartMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, USER_1_ID));
        assertEquals(12, item.getQuantity());
        assertEquals(MERCHANT_A_ID, CartHeaderTestFixture.merchantId(jdbcTemplate, USER_1_ID));
    }

    private void addConcurrently(AddCartDTO dto) {
        ConcurrentTestTemplate.runConcurrently(2, Duration.ofSeconds(10), workerIndex -> {
            try {
                UserContextHolder.setUserId(USER_1_ID);
                cartService.add(dto);
            } finally {
                UserContextHolder.clear();
            }
        });
    }

    @Test
    void decreasingLastItemToZeroUnbindsMerchantAndAllowsAnotherStore() {
        CartTestData products = createProductsAndDifferentMerchants();
        Long itemId = cartService.add(products.iphoneDTO()).getId();
        UpdateCartDTO decrease = new UpdateCartDTO();
        decrease.setCartItemId(itemId);
        decrease.setQuantityChange(-1);

        assertEquals(0, cartService.update(decrease).getQuantity());

        assertFalse(cartMapper.existsByUserId(USER_1_ID));
        assertNull(CartHeaderTestFixture.merchantId(jdbcTemplate, USER_1_ID));
        cartService.add(products.macbookDTO());
        assertEquals(MERCHANT_B_ID, CartHeaderTestFixture.merchantId(jdbcTemplate, USER_1_ID));
        assertEquals(1L, cartMapper.selectCount(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, USER_1_ID).eq(CartItem::getProductId, PRODUCT_B1_ID)));
    }

    @Test
    void decreasingOneItemToZeroKeepsBindingForRemainingItems() {
        CartTestData products = createProductsAndSameMerchant();
        Long removedId = cartService.add(products.iphoneDTO()).getId();
        Long remainingId = cartService.add(products.macbookDTO()).getId();
        UpdateCartDTO decrease = new UpdateCartDTO();
        decrease.setCartItemId(removedId);
        decrease.setQuantityChange(-1);

        assertEquals(0, cartService.update(decrease).getQuantity());

        assertNull(cartMapper.selectById(removedId));
        assertEquals(1, cartMapper.selectById(remainingId).getQuantity());
        assertEquals(MERCHANT_A_ID, CartHeaderTestFixture.merchantId(jdbcTemplate, USER_1_ID));
        assertTrue(cartService.list().getCanBuy());
    }

    private BusinessException addAttempt(AddCartDTO dto) {
        try {
            UserContextHolder.setUserId(USER_1_ID);
            cartService.add(dto);
            return null;
        } catch (BusinessException failure) {
            return failure;
        } finally {
            UserContextHolder.clear();
        }
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
        return new CartTestData(createAddCartDto(PRODUCT_A1_ID), createAddCartDto(PRODUCT_A2_ID));
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
        return new CartTestData(createAddCartDto(PRODUCT_A1_ID), createAddCartDto(PRODUCT_B1_ID));
    }

    private AddCartDTO createAddCartDto(Long productId) {
        AddCartDTO dto = new AddCartDTO();
        dto.setProductId(productId);
        return dto;
    }

    private void createCartAndProductFromSameMerchant() {
        Product iphone = TestDataFactory.createProduct(PRODUCT_A1_ID, PRODUCT_A1_NAME, 100, MERCHANT_A_ID);
        CartItem item = TestDataFactory.createCartItem(CART_ITEM_1_ID, USER_1_ID, iphone, 10);
        iphone.setCategoryId(CATEGORY_A_ID);
        merchantMapper.insert(TestDataFactory.createOpenMerchant(MERCHANT_A_ID));
        categoryMapper.insert(createCategory(CATEGORY_A_ID, MERCHANT_A_ID));
        productMapper.insert(iphone);
        cartMapper.insert(item);
        CartHeaderTestFixture.bind(jdbcTemplate, USER_1_ID, MERCHANT_A_ID);
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
