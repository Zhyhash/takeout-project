package org.example.takeout.User.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Product.Service.ProductService;
import org.example.takeout.Product.VO.ProductVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrowseHistoryServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ProductService productService;

    @InjectMocks
    private BrowseHistoryService browseHistoryService;

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void recordViewRejectsInvalidUserId(Long userId) {
        assertInvalidId(userId, "用户ID", id -> browseHistoryService.recordView(id, 21L));
        verifyNoInteractions(productService, redisTemplate);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void recordViewRejectsInvalidProductId(Long productId) {
        assertInvalidId(productId, "商品ID", id -> browseHistoryService.recordView(11L, id));
        verifyNoInteractions(productService, redisTemplate);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void getRecentViewedProductIdsRejectsInvalidUserId(Long userId) {
        assertInvalidId(userId, "用户ID", browseHistoryService::getRecentViewedProductIds);
        verifyNoInteractions(redisTemplate);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void clearHistoryRejectsInvalidUserId(Long userId) {
        assertInvalidId(userId, "用户ID", browseHistoryService::clearHistory);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void recordViewWritesHistoryForValidIds() {
        ProductVO product = productVO(21L);
        when(productService.getProductDetail(21L)).thenReturn(product);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(1L);

        assertSame(product, browseHistoryService.recordView(11L, 21L));

        var operations = inOrder(productService, redisTemplate);
        operations.verify(productService).getProductDetail(21L);
        operations.verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("history:product:11")),
                eq("21"),
                eq("2592000"),
                eq("50")
        );
    }

    @Test
    void recordViewContinuesWhenRedisWriteFails() {
        ProductVO product = productVO(21L);
        when(productService.getProductDetail(21L)).thenReturn(product);
        doThrow(new RedisConnectionFailureException("Redis unavailable"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any());

        assertSame(product, browseHistoryService.recordView(11L, 21L));
    }

    @Test
    void recordViewDoesNotWriteHistoryWhenProductIsMissing() {
        when(productService.getProductDetail(21L))
                .thenThrow(new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> browseHistoryService.recordView(11L, 21L)
        );

        assertEquals("商品不存在", exception.getMessage());
        verify(productService).getProductDetail(21L);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void getHistoryReturnsProductViewsInRedisOrderAndSkipsMissingProducts() {
        ProductVO newerProduct = productVO(21L);
        ProductVO olderProduct = productVO(19L);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("history:product:11", 0, -1))
                .thenReturn(List.of("21", "20", "19"));
        when(productService.getProductDetail(21L)).thenReturn(newerProduct);
        when(productService.getProductDetail(20L))
                .thenThrow(new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在"));
        when(productService.getProductDetail(19L)).thenReturn(olderProduct);

        assertEquals(List.of(newerProduct, olderProduct), browseHistoryService.getHistory(11L));

        var operations = inOrder(listOperations, productService);
        operations.verify(listOperations).range("history:product:11", 0, -1);
        operations.verify(productService).getProductDetail(21L);
        operations.verify(productService).getProductDetail(20L);
        operations.verify(productService).getProductDetail(19L);
    }

    @ParameterizedTest
    @MethodSource("productLookupFailures")
    void getHistoryDoesNotHideNonMissingProductErrors(RuntimeException failure) {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("history:product:11", 0, -1)).thenReturn(List.of("21"));
        when(productService.getProductDetail(21L)).thenThrow(failure);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> browseHistoryService.getHistory(11L)
        );

        assertSame(failure, exception);
    }

    @Test
    void getHistoryReturnsEmptyWithoutLookingUpProductsWhenHistoryIsEmpty() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("history:product:11", 0, -1)).thenReturn(List.of());

        assertEquals(List.of(), browseHistoryService.getHistory(11L));

        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void getHistoryRejectsInvalidUserId(Long userId) {
        assertInvalidId(userId, "用户ID", browseHistoryService::getHistory);
        verifyNoInteractions(productService, redisTemplate);
    }

    @Test
    void getRecentViewedProductIdsReturnsRedisOrderAsLongIds() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("history:product:11", 0, -1)).thenReturn(List.of("21", "20"));

        assertEquals(List.of(21L, 20L), browseHistoryService.getRecentViewedProductIds(11L));
    }

    @Test
    void getRecentViewedProductIdsMapsRedisFailureToServiceUnavailable() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("history:product:11", 0, -1))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> browseHistoryService.getRecentViewedProductIds(11L)
        );

        assertEquals(ResultCodeEnum.SERVICE_UNAVAILABLE, exception.getCodeEnum());
        assertEquals("浏览历史暂时不可用", exception.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    void getRecentViewedProductIdsReturnsEmptyForMissingHistory(List<String> ids) {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("history:product:11", 0, -1)).thenReturn(ids);

        assertEquals(List.of(), browseHistoryService.getRecentViewedProductIds(11L));
    }

    @Test
    void clearHistoryDeletesRedisKeyForValidUserId() {
        browseHistoryService.clearHistory(11L);

        verify(redisTemplate).delete("history:product:11");
    }

    @Test
    void clearHistoryMapsRedisFailureToServiceUnavailable() {
        when(redisTemplate.delete("history:product:11"))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> browseHistoryService.clearHistory(11L)
        );

        assertEquals(ResultCodeEnum.SERVICE_UNAVAILABLE, exception.getCodeEnum());
        assertEquals("清空浏览历史失败，请稍后重试", exception.getMessage());
    }

    private static void assertInvalidId(Long id, String idName, Consumer<Long> operation) {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> operation.accept(id)
        );

        assertEquals(ResultCodeEnum.PARAM_ERROR, exception.getCodeEnum());
        assertEquals(id == null ? idName + "不能为空" : idName + "必须大于0", exception.getMessage());
    }

    private static ProductVO productVO(Long id) {
        ProductVO product = new ProductVO();
        product.setId(id);
        product.setProductName("商品" + id);
        return product;
    }

    private static Stream<RuntimeException> productLookupFailures() {
        return Stream.of(
                new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品详情暂时不可用"),
                new BusinessException(ResultCodeEnum.SERVICE_UNAVAILABLE, "商品不存在"),
                new IllegalStateException("Product lookup failed")
        );
    }
}
