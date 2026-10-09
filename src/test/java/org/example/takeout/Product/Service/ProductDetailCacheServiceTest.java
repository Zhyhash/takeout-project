package org.example.takeout.Product.Service;

import org.example.takeout.CacheInvalidationTask.Service.CacheInvalidationTaskService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.Cache.RedisCacheClient;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductDetailCacheServiceTest {

    private static final Long PRODUCT_ID = 301L;
    private static final String CACHE_KEY = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
    private static final String LOCK_KEY = "Lock:" + CACHE_KEY;
    private static final String NULL_PRODUCT_CACHE = "__NULL__";

    @Mock
    private CacheInvalidationTaskService cacheInvalidationTaskService;

    @Mock
    private RedisCacheClient redisCacheClient;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProductDetailCacheService productDetailCacheService;

    @Test
    void cacheHitReturnsDeserializedProductWithoutTakingLockOrLoadingSource() throws Exception {
        ProductDetailCacheDTO cachedProduct = productDetail(true);
        Supplier<ProductDetailCacheDTO> supplier = mock(Supplier.class);
        when(redisCacheClient.get(CACHE_KEY)).thenReturn("cached-product");
        when(objectMapper.readValue("cached-product", ProductDetailCacheDTO.class))
                .thenReturn(cachedProduct);

        assertSame(cachedProduct,
                productDetailCacheService.getProductDetailCache(PRODUCT_ID, supplier));

        verify(redisCacheClient, never()).tryLock(anyString(), anyString(), anyLong(), any());
        verifyNoInteractions(supplier);
    }

    @Test
    void cachedNullMarkerReportsMissingProductWithoutLoadingSource() {
        Supplier<ProductDetailCacheDTO> supplier = mock(Supplier.class);
        when(redisCacheClient.get(CACHE_KEY)).thenReturn(NULL_PRODUCT_CACHE);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> productDetailCacheService.getProductDetailCache(PRODUCT_ID, supplier));

        assertEquals("商品不存在", exception.getMessage());
        verifyNoInteractions(supplier);
        verify(redisCacheClient, never()).tryLock(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void cacheMissLoadsSerializesAndCachesProductThenReleasesLock() throws Exception {
        ProductDetailCacheDTO loadedProduct = productDetail(true);
        when(redisCacheClient.get(CACHE_KEY)).thenReturn(null);
        when(redisCacheClient.tryLock(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(objectMapper.writeValueAsString(loadedProduct)).thenReturn("product-json");

        ProductDetailCacheDTO result = productDetailCacheService.getProductDetailCache(
                PRODUCT_ID, () -> loadedProduct);

        assertSame(loadedProduct, result);
        verify(objectMapper).writeValueAsString(loadedProduct);
        verify(redisCacheClient).set(
                eq(CACHE_KEY), eq("product-json"), anyLong(), eq(TimeUnit.MINUTES));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(redisCacheClient).tryLock(eq(LOCK_KEY), token.capture(), eq(10L), eq(TimeUnit.SECONDS));
        verify(redisCacheClient).unlock(LOCK_KEY, token.getValue());
    }

    @Test
    void missingProductWritesNullMarkerAndSubsequentReadSkipsSource() {
        @SuppressWarnings("unchecked")
        Supplier<ProductDetailCacheDTO> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn(null);
        when(redisCacheClient.get(CACHE_KEY)).thenReturn(null, null, NULL_PRODUCT_CACHE);
        when(redisCacheClient.tryLock(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        assertNull(productDetailCacheService.getProductDetailCache(PRODUCT_ID, supplier));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> productDetailCacheService.getProductDetailCache(PRODUCT_ID, supplier));

        assertEquals("商品不存在", exception.getMessage());
        verify(supplier, times(1)).get();
        verify(redisCacheClient).set(
                eq(CACHE_KEY), eq(NULL_PRODUCT_CACHE), anyLong(), eq(TimeUnit.MINUTES));
        verify(redisCacheClient).unlock(eq(LOCK_KEY), anyString());
    }

    @Test
    void cacheWriteFailureStillReturnsLoadedProductAndReleasesLock() throws Exception {
        ProductDetailCacheDTO loadedProduct = productDetail(true);
        when(redisCacheClient.get(CACHE_KEY)).thenReturn(null);
        when(redisCacheClient.tryLock(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(objectMapper.writeValueAsString(loadedProduct)).thenReturn("product-json");
        doThrow(new RedisCacheUnavailableException("Redis write failed", new IllegalStateException()))
                .when(redisCacheClient)
                .set(eq(CACHE_KEY), eq("product-json"), anyLong(), eq(TimeUnit.MINUTES));

        assertSame(loadedProduct, productDetailCacheService.getProductDetailCache(
                PRODUCT_ID, () -> loadedProduct));

        verify(redisCacheClient).set(
                eq(CACHE_KEY), eq("product-json"), anyLong(), eq(TimeUnit.MINUTES));
        verify(redisCacheClient).unlock(eq(LOCK_KEY), anyString());
    }

    @Test
    void nullMarkerWriteFailureStillReturnsMissingSourceResult() {
        when(redisCacheClient.get(CACHE_KEY)).thenReturn(null);
        when(redisCacheClient.tryLock(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        doThrow(new RedisCacheUnavailableException("Redis write failed", new IllegalStateException()))
                .when(redisCacheClient)
                .set(eq(CACHE_KEY), eq(NULL_PRODUCT_CACHE), anyLong(), eq(TimeUnit.MINUTES));

        assertNull(productDetailCacheService.getProductDetailCache(PRODUCT_ID, () -> null));

        verify(redisCacheClient).set(
                eq(CACHE_KEY), eq(NULL_PRODUCT_CACHE), anyLong(), eq(TimeUnit.MINUTES));
        verify(redisCacheClient).unlock(eq(LOCK_KEY), anyString());
    }

    @Test
    void cacheMissRetriesLockAndRebuildsAfterAcquiringIt() throws Exception {
        ProductDetailCacheDTO loadedProduct = productDetail(true);
        when(redisCacheClient.get(CACHE_KEY)).thenReturn(null);
        when(redisCacheClient.tryLock(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false, true);
        when(objectMapper.writeValueAsString(loadedProduct)).thenReturn("product-json");

        assertSame(loadedProduct, productDetailCacheService.getProductDetailCache(
                PRODUCT_ID, () -> loadedProduct));

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(redisCacheClient, times(2)).tryLock(
                eq(LOCK_KEY), token.capture(), eq(10L), eq(TimeUnit.SECONDS));
        assertEquals(token.getAllValues().get(0), token.getAllValues().get(1));
        verify(redisCacheClient, times(3)).get(CACHE_KEY);
        verify(redisCacheClient).set(
                eq(CACHE_KEY), eq("product-json"), anyLong(), eq(TimeUnit.MINUTES));
        verify(redisCacheClient).unlock(LOCK_KEY, token.getAllValues().get(1));
    }

    @Test
    void cacheMissTimesOutWithoutLoadingSource() {
        Supplier<ProductDetailCacheDTO> supplier = mock(Supplier.class);
        when(redisCacheClient.get(CACHE_KEY)).thenReturn(null);
        when(redisCacheClient.tryLock(eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> productDetailCacheService.getProductDetailCache(PRODUCT_ID, supplier));

        assertEquals("请求超时，请稍后重试", exception.getMessage());
        verify(redisCacheClient, times(6)).get(CACHE_KEY);
        verify(redisCacheClient, times(6)).tryLock(
                eq(LOCK_KEY), anyString(), eq(10L), eq(TimeUnit.SECONDS));
        verify(redisCacheClient, never()).unlock(anyString(), anyString());
        verifyNoInteractions(supplier);
    }

    @Test
    void evictRequestsInvalidationForProductDetailKey() {
        productDetailCacheService.evictProductDetailCache(PRODUCT_ID);

        verify(cacheInvalidationTaskService).requestInvalidation(CACHE_KEY);
        verifyNoInteractions(redisCacheClient);
    }

    private ProductDetailCacheDTO productDetail(boolean inStock) {
        ProductDetailCacheDTO product = new ProductDetailCacheDTO();
        product.setId(PRODUCT_ID);
        product.setMerchantId(201L);
        product.setInStock(inStock);
        return product;
    }
}
