package org.example.takeout.Cart.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisHashCartServiceExperimentTest {

    private static final Long USER_ID = 123L;
    private static final Long PRODUCT_ID = 456L;
    private static final String CART_KEY = "cart:123";
    private static final String PRODUCT_FIELD = "456";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private RedisHashCartServiceExperiment service;

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = "GET_CART", mode = EnumSource.Mode.EXCLUDE)
    void rejectsNullUserIdBeforeAccessingRedis(Operation operation) {
        assertBusinessException("userID为空", () -> invoke(operation, null, PRODUCT_ID));

        verifyNoInteractions(redisTemplate, hashOperations);
    }

    @Test
    void getCartRejectsNullUserIdBeforeReadingHashEntries() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        assertBusinessException("userID为空", () -> service.getCart(null));

        // Obtaining the HashOperations wrapper does not issue a Redis command.
        verifyNoInteractions(hashOperations);
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"ADD", "GET_QUANTITY", "REMOVE", "DECREASE"})
    void rejectsNullProductIdBeforeAccessingRedis(Operation operation) {
        assertBusinessException("productID为空", () -> invoke(operation, USER_ID, null));

        verifyNoInteractions(redisTemplate, hashOperations);
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"ADD", "DECREASE"})
    void reportsBusinessErrorWhenScriptReturnsNull(Operation operation) {
        givenScriptResult(null);

        assertBusinessException("购物车操作异常", () -> invoke(operation, USER_ID, PRODUCT_ID));

        verifyScriptArguments();
    }

    @Test
    void addReportsQuantityLimitWhenScriptReturnsMinusOne() {
        givenScriptResult(-1L);

        assertBusinessException("商品增加超过上限", () -> service.add(USER_ID, PRODUCT_ID));

        verifyScriptArguments();
    }

    @Test
    void decreaseReportsMissingProductWhenScriptReturnsMinusOne() {
        givenScriptResult(-1L);

        assertBusinessException("商品不存在于购物车", () -> service.decrease(USER_ID, PRODUCT_ID));

        verifyScriptArguments();
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 99L})
    void addReturnsQuantityReportedByScript(long quantity) {
        givenScriptResult(quantity);

        assertEquals(Long.valueOf(quantity), service.add(USER_ID, PRODUCT_ID));

        verifyScriptArguments();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 98L})
    void decreaseReturnsQuantityAsInteger(long quantity) {
        givenScriptResult(quantity);

        assertEquals(Integer.valueOf((int) quantity), service.decrease(USER_ID, PRODUCT_ID));

        verifyScriptArguments();
    }

    @Test
    void getQuantityReportsMissingProduct() {
        givenHashOperations();
        when(hashOperations.get(CART_KEY, PRODUCT_FIELD)).thenReturn(null);

        assertBusinessException("商品不存在于购物车", () -> service.getQuantity(USER_ID, PRODUCT_ID));

        verify(hashOperations).get(CART_KEY, PRODUCT_FIELD);
    }

    @ParameterizedTest
    @MethodSource("storedQuantities")
    void getQuantityConvertsStoredValueToInteger(Object storedValue, Integer expectedQuantity) {
        givenHashOperations();
        when(hashOperations.get(CART_KEY, PRODUCT_FIELD)).thenReturn(storedValue);

        assertEquals(expectedQuantity, service.getQuantity(USER_ID, PRODUCT_ID));

        verify(hashOperations).get(CART_KEY, PRODUCT_FIELD);
    }

    @Test
    void getCartReturnsEmptyMapWhenHashHasNoEntries() {
        givenHashOperations();
        when(hashOperations.entries(CART_KEY)).thenReturn(Map.of());

        assertTrue(service.getCart(USER_ID).isEmpty());

        verify(hashOperations).entries(CART_KEY);
    }

    @Test
    void getCartConvertsAllProductIdsAndQuantities() {
        givenHashOperations();
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("456", "2");
        entries.put(789L, 99);
        when(hashOperations.entries(CART_KEY)).thenReturn(entries);

        assertEquals(Map.of(456L, 2, 789L, 99), service.getCart(USER_ID));

        verify(hashOperations).entries(CART_KEY);
    }

    @Test
    void removeDeletesRequestedProductFromUserCart() {
        givenHashOperations();

        service.remove(USER_ID, PRODUCT_ID);

        verify(hashOperations).delete(CART_KEY, PRODUCT_FIELD);
    }

    @Test
    void clearDeletesUserCartKey() {
        service.clear(USER_ID);

        verify(redisTemplate).delete(CART_KEY);
    }

    private static Stream<Arguments> storedQuantities() {
        return Stream.of(
                Arguments.of("1", 1),
                Arguments.of("99", 99),
                Arguments.of(7, 7),
                Arguments.of(8L, 8));
    }

    private void givenHashOperations() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private void givenScriptResult(Long result) {
        doReturn(result).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any());
    }

    private void verifyScriptArguments() {
        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of(CART_KEY)), eq(PRODUCT_FIELD), any());
    }

    private static void assertBusinessException(String message, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertAll(
                () -> assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum()),
                () -> assertEquals(ResultCodeEnum.BUSINESS_ERROR.getCode(), exception.getCode()),
                () -> assertEquals(message, exception.getMessage()));
    }

    private void invoke(Operation operation, Long userId, Long productId) {
        switch (operation) {
            case ADD -> service.add(userId, productId);
            case GET_QUANTITY -> service.getQuantity(userId, productId);
            case GET_CART -> service.getCart(userId);
            case REMOVE -> service.remove(userId, productId);
            case CLEAR -> service.clear(userId);
            case DECREASE -> service.decrease(userId, productId);
        }
    }

    private enum Operation {
        ADD, GET_QUANTITY, GET_CART, REMOVE, CLEAR, DECREASE
    }
}
