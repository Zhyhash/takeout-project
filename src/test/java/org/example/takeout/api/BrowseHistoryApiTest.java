package org.example.takeout.api;

import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Product.VO.ProductVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BrowseHistoryApiTest extends AbstractMockMvcApiTest {

    private static final String HISTORY_PATH = "/user/browse-history";

    @Autowired
    BrowseHistoryApiTest(JWTUtils jwtUtils, WebApplicationContext webApplicationContext) {
        super(jwtUtils, webApplicationContext);
    }

    @Test
    void getHistoryDelegatesToBrowseHistoryServiceAndReturnsItsProductViews() throws Exception {
        ProductVO newerProduct = productVO(302L, "Newer Rice");
        ProductVO olderProduct = productVO(301L, "Older Rice");
        when(browseHistoryService.getHistory(101L)).thenReturn(List.of(newerProduct, olderProduct));

        mockMvc.perform(request("GET", HISTORY_PATH, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(302))
                .andExpect(jsonPath("$.data[0].productName").value("Newer Rice"))
                .andExpect(jsonPath("$.data[0].imageUrl").value("/products/302.png"))
                .andExpect(jsonPath("$.data[0].inStock").value(true))
                .andExpect(jsonPath("$.data[0].status").value(0))
                .andExpect(jsonPath("$.data[0].statusDesc").value("正在销售"))
                .andExpect(jsonPath("$.data[0].price").value(18.80))
                .andExpect(jsonPath("$.data[1].id").value(301))
                .andExpect(jsonPath("$.data[1].productName").value("Older Rice"));

        verify(browseHistoryService).getHistory(101L);
        verifyNoInteractions(productService);
    }

    @Test
    void getHistoryReturnsEmptyListWhenServiceHasNoProducts() throws Exception {
        when(browseHistoryService.getHistory(101L)).thenReturn(List.of());

        mockMvc.perform(request("GET", HISTORY_PATH, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(browseHistoryService).getHistory(101L);
        verifyNoInteractions(productService);
    }

    @Test
    void getHistoryPropagatesBusinessErrorFromService() throws Exception {
        when(browseHistoryService.getHistory(101L))
                .thenThrow(businessError("浏览历史查询失败"));

        mockMvc.perform(request("GET", HISTORY_PATH, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR))
                .andExpect(jsonPath("$.message").value("浏览历史查询失败"));

        verifyNoInteractions(productService);
    }

    @Test
    void recordViewDelegatesToBrowseHistoryServiceAndReturnsItsProductView() throws Exception {
        ProductVO product = productVO(302L, "Rice");
        when(browseHistoryService.recordView(101L, 302L)).thenReturn(product);

        mockMvc.perform(request("POST", HISTORY_PATH + "/302", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data.id").value(302))
                .andExpect(jsonPath("$.data.productName").value("Rice"));

        verify(browseHistoryService).recordView(101L, 302L);
        verifyNoInteractions(productService);
    }

    @Test
    void recordViewPropagatesBusinessErrorFromService() throws Exception {
        when(browseHistoryService.recordView(101L, 999L))
                .thenThrow(businessError("商品不存在"));

        mockMvc.perform(request("POST", HISTORY_PATH + "/999", userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(BUSINESS_ERROR))
                .andExpect(jsonPath("$.message").value("商品不存在"));

        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void recordViewRejectsNonPositiveProductId(long productId) throws Exception {
        mockMvc.perform(request("POST", HISTORY_PATH + "/" + productId, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(PARAM_ERROR));

        verifyNoInteractions(browseHistoryService, productService);
    }

    @Test
    void deleteHistoryClearsHistoryForTokenUser() throws Exception {
        mockMvc.perform(request("DELETE", HISTORY_PATH, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(SUCCESS))
                .andExpect(jsonPath("$.data").value("清空成功"));

        verify(browseHistoryService).clearHistory(101L);
        verifyNoInteractions(productService);
    }

    @Test
    void getHistoryPropagatesRedisUnavailableResponse() throws Exception {
        when(browseHistoryService.getHistory(101L)).thenThrow(
                new BusinessException(ResultCodeEnum.SERVICE_UNAVAILABLE, "浏览历史暂时不可用"));

        mockMvc.perform(request("GET", HISTORY_PATH, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(ResultCodeEnum.SERVICE_UNAVAILABLE.getCode()));

        verifyNoInteractions(productService);
    }

    @Test
    void deleteHistoryPropagatesRedisUnavailableResponse() throws Exception {
        doThrow(new BusinessException(ResultCodeEnum.SERVICE_UNAVAILABLE, "清空浏览历史失败，请稍后重试"))
                .when(browseHistoryService).clearHistory(101L);

        mockMvc.perform(request("DELETE", HISTORY_PATH, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(ResultCodeEnum.SERVICE_UNAVAILABLE.getCode()));

        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @MethodSource("browseHistoryRoutes")
    void browseHistoryRoutesRejectMissingAndInvalidTokens(String method, String path) throws Exception {
        for (String token : new String[]{null, invalidBearer()}) {
            mockMvc.perform(request(method, path, token))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(UNAUTHORIZED));
        }

        verifyNoInteractions(browseHistoryService, productService);
    }

    @ParameterizedTest
    @MethodSource("browseHistoryRoutes")
    void browseHistoryRoutesRejectMerchantAndRiderTokens(String method, String path) throws Exception {
        for (String token : new String[]{merchantBearer(), riderBearer()}) {
            mockMvc.perform(request(method, path, token))
                    .andExpect(status().isOk())
                    .andExpect(resultCode(UNAUTHORIZED));
        }

        verifyNoInteractions(browseHistoryService, productService);
    }

    @ParameterizedTest
    @MethodSource("browseHistoryRoutes")
    void browseHistoryRoutesRejectDisabledUser(String method, String path) throws Exception {
        doThrow(new AuthException("用户账号已禁用或不存在，请重新登录"))
                .when(userService).requireActiveUserId();

        mockMvc.perform(request(method, path, userBearer()))
                .andExpect(status().isOk())
                .andExpect(resultCode(UNAUTHORIZED));

        verifyNoInteractions(browseHistoryService, productService);
    }

    private MockHttpServletRequestBuilder request(String method, String path, String token) {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        if (token != null) {
            request.header("Authorization", token);
        }
        // The endpoint must always use the identity from the token, never a client supplied user ID.
        return request.param("userId", "999");
    }

    private static Stream<Arguments> browseHistoryRoutes() {
        return Stream.of(
                Arguments.of("GET", HISTORY_PATH),
                Arguments.of("POST", HISTORY_PATH + "/301"),
                Arguments.of("DELETE", HISTORY_PATH)
        );
    }

    private ProductVO productVO(Long id, String name) {
        ProductVO product = new ProductVO();
        product.setId(id);
        product.setProductName(name);
        product.setImageUrl("/products/" + id + ".png");
        product.setInStock(true);
        product.setStatus(0);
        product.setStatusDesc("正在销售");
        product.setPrice(new BigDecimal("18.80"));
        return product;
    }
}
