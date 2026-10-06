package org.example.takeout.Cart.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.example.takeout.testsupport.ConcurrentTestTemplate.runConcurrently;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接连接真实 Redis，仅加载此实验类及 Redis Bean，不依赖 MySQL 或完整应用。
 * 通过 Hash 预置数据，使读取、删除、减少测试不依赖 add 是否正确。
 * 使用随机负数用户 ID 隔离测试数据，结束时仅清理本次测试使用的两个 Key。
 */
@SpringJUnitConfig(
        classes = RedisHashCartServiceExperimentIntegrationTest.RedisOnlyConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@Timeout(20)
class RedisHashCartServiceExperimentIntegrationTest {

    private static final long PRODUCT_ID = 2001L;
    private static final long OTHER_PRODUCT_ID = 2002L;
    private static final long CART_TTL_MILLIS = 1_800_000L;
    private static final Duration SHORT_TTL = Duration.ofSeconds(30);

    @Autowired
    private RedisHashCartServiceExperiment service;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        assertEquals("PONG", redisTemplate.execute(
                (RedisCallback<String>) connection -> connection.ping()),
                "真实 Redis 必须可用；连接失败不能作为测试通过或跳过");
        userId = unusedUserId();
        do {
            otherUserId = unusedUserId();
        } while (otherUserId.equals(userId));
    }

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            redisTemplate.delete(key(userId));
        }
        if (otherUserId != null) {
            redisTemplate.delete(key(otherUserId));
        }
    }

    @Test
    void addCreatesHashWithQuantityOneAndThirtyMinuteTtl() {
        assertEquals(1L, service.add(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "1"), rawCart(userId));
        assertFullTtl(userId);
    }

    @Test
    void addIncrementsExistingProductAndPreservesOtherProducts() {
        seedCart(userId, Map.of(PRODUCT_ID, 2, OTHER_PRODUCT_ID, 5));

        assertEquals(3L, service.add(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "3", "2002", "5"), rawCart(userId));
        assertFullTtl(userId);
    }

    @Test
    void addAllowsQuantityToReachNinetyNine() {
        seedCart(userId, Map.of(PRODUCT_ID, 98));

        assertEquals(99L, service.add(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "99"), rawCart(userId));
    }

    @Test
    void addRejectsQuantityAboveLimitWithoutChangingCart() {
        seedCart(userId, Map.of(PRODUCT_ID, 99, OTHER_PRODUCT_ID, 5));

        assertBusinessError("商品增加超过上限", () -> service.add(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "99", "2002", "5"), rawCart(userId));
    }

    @Test
    void addKeepsDifferentUsersIsolated() {
        seedCart(otherUserId, Map.of(PRODUCT_ID, 7));

        assertEquals(1L, service.add(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "1"), rawCart(userId));
        assertEquals(Map.of("2001", "7"), rawCart(otherUserId));
    }

    @Test
    void concurrentAddsDoNotLoseIncrements() {
        int workers = 24;
        Set<Long> returnedQuantities = ConcurrentHashMap.newKeySet();

        runConcurrently(workers, Duration.ofSeconds(10), worker ->
                assertTrue(returnedQuantities.add(service.add(userId, PRODUCT_ID)),
                        "每次原子增加应返回不同的新数量"));

        assertEquals(workers, returnedQuantities.size());
        for (long quantity = 1; quantity <= workers; quantity++) {
            assertTrue(returnedQuantities.contains(quantity));
        }
        assertEquals(Map.of("2001", "24"), rawCart(userId));
        assertFullTtl(userId);
    }

    @Test
    void concurrentAddsCannotExceedNinetyNine() {
        seedCart(userId, Map.of(PRODUCT_ID, 95));
        AtomicInteger rejected = new AtomicInteger();
        Set<Long> returnedQuantities = ConcurrentHashMap.newKeySet();

        runConcurrently(12, Duration.ofSeconds(10), worker -> {
            try {
                assertTrue(returnedQuantities.add(service.add(userId, PRODUCT_ID)));
            } catch (BusinessException exception) {
                assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum());
                assertEquals("商品增加超过上限", exception.getMessage());
                rejected.incrementAndGet();
            }
        });

        assertEquals(Set.of(96L, 97L, 98L, 99L), returnedQuantities);
        assertEquals(8, rejected.get());
        assertEquals(Map.of("2001", "99"), rawCart(userId));
    }

    @Test
    void getQuantityReadsStoredIntegerAndRefreshesTtl() {
        seedCart(userId, Map.of(PRODUCT_ID, 12));

        assertEquals(12, service.getQuantity(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "12"), rawCart(userId));
        assertFullTtl(userId);
    }

    @Test
    void getQuantityRejectsMissingCartWithoutCreatingKey() {
        assertBusinessError("商品不存在于购物车", () -> service.getQuantity(userId, PRODUCT_ID));

        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    @Test
    void getQuantityRejectsMissingProductWithoutChangingOtherProducts() {
        seedCart(userId, Map.of(OTHER_PRODUCT_ID, 5));

        assertBusinessError("商品不存在于购物车", () -> service.getQuantity(userId, PRODUCT_ID));

        assertEquals(Map.of("2002", "5"), rawCart(userId));
    }

    @Test
    void getCartReturnsEmptyMapWithoutCreatingKey() {
        assertEquals(Map.of(), service.getCart(userId));

        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    @Test
    void getCartConvertsAllProductsAndKeepsUsersIsolated() {
        seedCart(userId, Map.of(PRODUCT_ID, 3, OTHER_PRODUCT_ID, 8));
        seedCart(otherUserId, Map.of(PRODUCT_ID, 7));

        assertEquals(Map.of(PRODUCT_ID, 3, OTHER_PRODUCT_ID, 8), service.getCart(userId));
        assertEquals(Map.of(PRODUCT_ID, 7), service.getCart(otherUserId));

        assertEquals(Map.of("2001", "3", "2002", "8"), rawCart(userId));
    }

    @Test
    void getCartRefreshesThirtyMinuteTtlAsConfirmedByUser() {
        // 用户确认：查询单个商品和查询整个购物车都应续期。
        seedCart(userId, Map.of(PRODUCT_ID, 3));

        assertEquals(Map.of(PRODUCT_ID, 3), service.getCart(userId));

        assertFullTtl(userId);
    }

    @Test
    void removeDeletesOnlyRequestedProductAndRefreshesRemainingCartTtl() {
        seedCart(userId, Map.of(PRODUCT_ID, 3, OTHER_PRODUCT_ID, 8));
        seedCart(otherUserId, Map.of(PRODUCT_ID, 7));

        service.remove(userId, PRODUCT_ID);

        assertEquals(Map.of("2002", "8"), rawCart(userId));
        assertEquals(Map.of("2001", "7"), rawCart(otherUserId));
        assertFullTtl(userId);
    }

    @Test
    void removeLastProductDeletesHashKey() {
        seedCart(userId, Map.of(PRODUCT_ID, 1));

        service.remove(userId, PRODUCT_ID);

        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    @Test
    void removeMissingProductPreservesExistingProducts() {
        seedCart(userId, Map.of(OTHER_PRODUCT_ID, 8));

        service.remove(userId, PRODUCT_ID);

        assertEquals(Map.of("2002", "8"), rawCart(userId));
    }

    @Test
    void removeMissingCartIsIdempotentAndDoesNotCreateKey() {
        service.remove(userId, PRODUCT_ID);
        service.remove(userId, PRODUCT_ID);

        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    @Test
    void clearDeletesWholeCartAndPreservesAnotherUsersCart() {
        seedCart(userId, Map.of(PRODUCT_ID, 3, OTHER_PRODUCT_ID, 8));
        seedCart(otherUserId, Map.of(PRODUCT_ID, 7));

        service.clear(userId);

        assertFalse(redisTemplate.hasKey(key(userId)));
        assertEquals(Map.of("2001", "7"), rawCart(otherUserId));
    }

    @Test
    void clearMissingCartIsIdempotentAndDoesNotCreateKey() {
        service.clear(userId);
        service.clear(userId);

        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    @Test
    void decreaseSubtractsOneAndRefreshesTtl() {
        seedCart(userId, Map.of(PRODUCT_ID, 3, OTHER_PRODUCT_ID, 8));

        assertEquals(2, service.decrease(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "2", "2002", "8"), rawCart(userId));
        assertFullTtl(userId);
    }

    @Test
    void decreaseFromTwoKeepsProductWithQuantityOne() {
        seedCart(userId, Map.of(PRODUCT_ID, 2));

        assertEquals(1, service.decrease(userId, PRODUCT_ID));

        assertEquals(Map.of("2001", "1"), rawCart(userId));
    }

    @Test
    void decreaseLuaUpdatesQuantityWhenArgumentsAreStrings() {
        // 单独执行原有脚本，避免 Java 参数序列化问题遮住 Lua 本身的错误。
        seedCart(userId, Map.of(PRODUCT_ID, 3));
        RedisScript<?> script = (RedisScript<?>) ReflectionTestUtils.getField(
                RedisHashCartServiceExperiment.class, "decreaseScript");
        assertNotNull(script);

        Object newQuantity = redisTemplate.execute(script, List.of(key(userId)),
                String.valueOf(PRODUCT_ID), String.valueOf(CART_TTL_MILLIS));

        assertEquals(2L, newQuantity);
        assertEquals(Map.of("2001", "2"), rawCart(userId));
        assertFullTtl(userId);
    }

    @Test
    void decreaseFromOneRemovesOnlyRequestedProductAndReturnsZero() {
        seedCart(userId, Map.of(PRODUCT_ID, 1, OTHER_PRODUCT_ID, 8));
        seedCart(otherUserId, Map.of(PRODUCT_ID, 7));

        assertEquals(0, service.decrease(userId, PRODUCT_ID));

        assertEquals(Map.of("2002", "8"), rawCart(userId));
        assertEquals(Map.of("2001", "7"), rawCart(otherUserId));
        assertFullTtl(userId);
    }

    @Test
    void decreaseLastProductDeletesHashKeyAndReturnsZero() {
        seedCart(userId, Map.of(PRODUCT_ID, 1));

        assertEquals(0, service.decrease(userId, PRODUCT_ID));

        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    @Test
    void decreaseRejectsMissingCartWithoutCreatingKey() {
        assertBusinessError("商品不存在于购物车", () -> service.decrease(userId, PRODUCT_ID));

        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    @Test
    void decreaseRejectsMissingProductWithoutChangingOtherProducts() {
        seedCart(userId, Map.of(OTHER_PRODUCT_ID, 8));

        assertBusinessError("商品不存在于购物车", () -> service.decrease(userId, PRODUCT_ID));

        assertEquals(Map.of("2002", "8"), rawCart(userId));
    }

    @Test
    void concurrentDecreasesCannotCreateZeroOrNegativeStoredQuantity() {
        seedCart(userId, Map.of(PRODUCT_ID, 24));
        Set<Integer> returnedQuantities = ConcurrentHashMap.newKeySet();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(32, Duration.ofSeconds(10), worker -> {
            try {
                assertTrue(returnedQuantities.add(service.decrease(userId, PRODUCT_ID)));
            } catch (BusinessException exception) {
                assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum());
                assertEquals("商品不存在于购物车", exception.getMessage());
                rejected.incrementAndGet();
            }
        });

        assertEquals(24, returnedQuantities.size());
        for (int quantity = 0; quantity < 24; quantity++) {
            assertTrue(returnedQuantities.contains(quantity));
        }
        assertEquals(8, rejected.get());
        assertFalse(redisTemplate.hasKey(key(userId)));
    }

    private Long unusedUserId() {
        long candidate;
        do {
            candidate = -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        } while (Boolean.TRUE.equals(redisTemplate.hasKey(key(candidate))));
        return candidate;
    }

    private String key(Long id) {
        return "cart:" + id;
    }

    private void seedCart(Long id, Map<Long, Integer> quantities) {
        quantities.forEach((productId, quantity) -> redisTemplate.opsForHash()
                .put(key(id), productId.toString(), quantity.toString()));
        assertTrue(redisTemplate.expire(key(id), SHORT_TTL));
    }

    private Map<Object, Object> rawCart(Long id) {
        return redisTemplate.opsForHash().entries(key(id));
    }

    private void assertFullTtl(Long id) {
        Long remainingMillis = redisTemplate.getExpire(key(id), TimeUnit.MILLISECONDS);
        assertNotNull(remainingMillis);
        assertTrue(remainingMillis > CART_TTL_MILLIS - 10_000
                        && remainingMillis <= CART_TTL_MILLIS,
                "购物车应续期至 30 分钟，当前剩余 " + remainingMillis + " 毫秒");
    }

    private void assertBusinessError(String message, Runnable operation) {
        BusinessException exception = assertThrows(BusinessException.class, operation::run);
        assertEquals(ResultCodeEnum.BUSINESS_ERROR, exception.getCodeEnum());
        assertEquals(message, exception.getMessage());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisOnlyConfiguration {

        @Bean
        LettuceConnectionFactory redisConnectionFactory(
                @Value("${spring.data.redis.host:127.0.0.1}") String host,
                @Value("${spring.data.redis.port:6379}") int port,
                @Value("${spring.data.redis.database:0}") int database,
                @Value("${spring.data.redis.password:}") String password) {
            RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
            server.setDatabase(database);
            if (!password.isEmpty()) {
                server.setPassword(password);
            }
            LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofSeconds(2))
                    .shutdownTimeout(Duration.ZERO)
                    .build();
            return new LettuceConnectionFactory(server, client);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }

        @Bean
        RedisHashCartServiceExperiment cartService(StringRedisTemplate redisTemplate) {
            return new RedisHashCartServiceExperiment(redisTemplate);
        }
    }
}
