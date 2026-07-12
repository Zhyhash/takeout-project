package org.example.takeout.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.takeout.Cart.Service.CartService;
import org.example.takeout.Cart.VO.CartListVO;
import org.example.takeout.Cart.VO.CartVO;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Category.VO.CategoryVO;
import org.example.takeout.Category.VO.CreateCategoryVO;
import org.example.takeout.Common.Auth.AuthRole;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Merchant.Service.MerchantQueryService;
import org.example.takeout.Merchant.Service.MerchantService;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Merchant.VO.loginVO;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Order.VO.OrderDetailVO;
import org.example.takeout.Order.VO.OrderVO;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.User.Service.UserService;
import org.example.takeout.User.VO.LoginVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
abstract class AbstractMockMvcApiTest {

    static final int SUCCESS = 200;
    static final int PARAM_ERROR = 400;
    static final int UNAUTHORIZED = 401;
    static final int BUSINESS_ERROR = 500;
    static final int UNKNOWN_ERROR = -1;

    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private JWTUtils jwtUtils;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    protected UserService userService;

    @MockitoBean
    protected MerchantService merchantService;

    @MockitoBean
    protected MerchantQueryService merchantQueryService;

    @MockitoBean
    protected CategoryService categoryService;

    @MockitoBean
    protected ProductService productService;

    @MockitoBean
    protected CartService cartService;

    @MockitoBean
    protected OrderService orderService;

    @MockitoBean
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpMockMvcAndValidationDefaults() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    }

    @AfterEach
    void resetMocks() {
        reset(userService, merchantService, merchantQueryService, categoryService,
                productService, cartService, orderService, jdbcTemplate);
    }

    protected String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    protected String userBearer() {
        return "Bearer " + jwtUtils.createToken(101L, AuthRole.USER);
    }

    protected String merchantBearer() {
        return "Bearer " + jwtUtils.createToken(201L, AuthRole.MERCHANT);
    }

    protected String invalidBearer() {
        return "Bearer invalid-token";
    }

    protected ResultMatcher resultCode(int expectedCode) {
        return jsonPath("$.code").value(expectedCode);
    }

    protected ResultMatcher successMessage() {
        return jsonPath("$.message").value("success");
    }

    protected BusinessException businessError(String message) {
        return new BusinessException(ResultCodeEnum.BUSINESS_ERROR, message);
    }

    protected LoginVO userLoginVO() {
        LoginVO vo = new LoginVO();
        vo.setId(101L);
        vo.setNickname("tester");
        vo.setToken("user-token");
        return vo;
    }

    protected loginVO merchantLoginVO() {
        loginVO vo = new loginVO();
        vo.setId(201L);
        vo.setToken("merchant-token");
        return vo;
    }

    protected MerchantUpdateVO merchantUpdateVO() {
        MerchantUpdateVO vo = new MerchantUpdateVO();
        vo.setMerchantName("Test Shop");
        vo.setAddress("No.1 Test Road");
        vo.setPhone("13800138000");
        vo.setStatus(0);
        return vo;
    }

    protected CategoryVO categoryVO(long id, String name) {
        CategoryVO vo = new CategoryVO();
        vo.setId(id);
        vo.setCategoryName(name);
        return vo;
    }

    protected CreateCategoryVO createCategoryVO(long id, String name) {
        CreateCategoryVO vo = new CreateCategoryVO();
        vo.setId(id);
        vo.setCategoryName(name);
        vo.setStatusDesc("active");
        return vo;
    }

    protected MerchantProductVO merchantProductVO(long id, String name) {
        MerchantProductVO vo = new MerchantProductVO();
        vo.setId(id);
        vo.setProductName(name);
        vo.setCategoryName("Main");
        vo.setPrice(new BigDecimal("18.80"));
        vo.setStock(20);
        vo.setStatus(0);
        vo.setDescription("fresh");
        return vo;
    }

    protected CartVO cartVO(long id) {
        CartVO vo = new CartVO();
        vo.setId(id);
        vo.setProductId(301L);
        vo.setProductName("Rice");
        vo.setPrice(new BigDecimal("12.50"));
        vo.setQuantity(2);
        vo.setSubtotal(new BigDecimal("25.00"));
        vo.setAvailable(true);
        return vo;
    }

    protected CartListVO cartListVO() {
        CartListVO vo = new CartListVO();
        vo.setItems(Collections.singletonList(cartVO(401L)));
        vo.setTotalAmount(new BigDecimal("25.00"));
        return vo;
    }

    protected CreateOrderVO createOrderVO() {
        CreateOrderVO vo = new CreateOrderVO();
        vo.setOrderId(501L);
        vo.setOrderNo("ORD-TEST-001");
        return vo;
    }

    protected OrderVO orderVO(long id) {
        OrderVO vo = new OrderVO();
        vo.setId(id);
        vo.setOrderNo("ORD-TEST-" + id);
        vo.setMerchantName("Test Shop");
        vo.setTotalAmount(new BigDecimal("25.00"));
        vo.setProductSummary("Rice");
        vo.setStatus(0);
        return vo;
    }

    protected OrderDetailVO orderDetailVO(long id) {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setId(id);
        vo.setOrderNo("ORD-TEST-" + id);
        vo.setMerchantId(201L);
        vo.setMerchantName("Test Shop");
        vo.setReceiverName("Tester");
        vo.setReceiverPhone("13800138000");
        vo.setReceiverAddress("No.1 Test Road");
        vo.setOriginalAmount(new BigDecimal("25.00"));
        vo.setDiscountAmount(BigDecimal.ZERO);
        vo.setTotalAmount(new BigDecimal("25.00"));
        vo.setStatus(0);
        vo.setItems(Collections.emptyList());
        return vo;
    }
}
