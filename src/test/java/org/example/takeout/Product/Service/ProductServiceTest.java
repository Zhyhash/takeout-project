package org.example.takeout.Product.Service;

import org.example.takeout.CacheInvalidationTask.Service.CacheInvalidationTaskService;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Cache.ProductCacheService;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.Product.VO.ProductVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Long MERCHANT_ID = 201L;
    private static final Long PRODUCT_ID = 301L;
    private static final String NULL_PRODUCT_CACHE = "__NULL__";

    @Mock
    private ProductMapper productMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private ProductConverter productConverter;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ProductCacheService productCacheService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CacheInvalidationTaskService cacheInvalidationTaskService;

    @InjectMocks
    private ProductService productService;

    @BeforeEach
    void setUp() {
        MerchantContextHolder.setMerchantId(MERCHANT_ID);
    }

    @AfterEach
    void tearDown() {
        MerchantContextHolder.clear();
    }

    @Test
    void deleteProductLogicallyDeletesOwnedProductAndRequestsCacheInvalidation() {
        when(productMapper.delete(any())).thenReturn(1);

        productService.deleteProduct(PRODUCT_ID);

        verify(productMapper).delete(any());
        String cacheKey = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
        verify(cacheInvalidationTaskService).requestInvalidation(cacheKey);
        verify(productCacheService, never()).delete(cacheKey);
    }

    @Test
    void deleteProductRejectsMissingDeletedOrForeignProduct() {
        when(productMapper.delete(any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> productService.deleteProduct(PRODUCT_ID));

        verify(cacheInvalidationTaskService, never()).requestInvalidation(anyString());
        verify(productCacheService, never()).delete(any(String.class));
    }

    @Test
    void createProductReportsDuplicateActiveNameAsBusinessError() {
        Category category = new Category();
        category.setId(1L);

        CreateProductDTO dto = new CreateProductDTO();
        dto.setCategoryId(category.getId());
        dto.setProductName("可乐");

        Product product = new Product();
        product.setProductName(dto.getProductName());

        when(categoryMapper.selectOne(any())).thenReturn(category);
        when(productConverter.toProduct(dto, MERCHANT_ID)).thenReturn(product);
        doThrow(new DuplicateKeyException("duplicate active product name"))
                .when(productMapper)
                .insert(product);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.createProduct(dto)
        );

        assertEquals("当前店铺已存在同名商品", exception.getMessage());
        verify(productMapper).insert(product);
    }

    @Test
    void createProductReturnsInitialVersion() {
        Category category = new Category();
        category.setId(1L);
        category.setCategoryName("主食");
        CreateProductDTO dto = new CreateProductDTO();
        dto.setCategoryId(1L);
        dto.setProductName("米饭");
        ReflectionTestUtils.setField(productService, "productConverter",
                Mappers.getMapper(ProductConverter.class));
        when(categoryMapper.selectOne(any())).thenReturn(category);
        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(PRODUCT_ID);
            return 1;
        });

        MerchantProductVO created = productService.createProduct(dto);

        assertEquals(PRODUCT_ID, created.getId());
        assertEquals(0, created.getVersion());
    }

    @Test
    void merchantDetailReadsCurrentVersionFromDatabase() {
        Product product = product(ProductStatusEnum.OFF_SALE.getCode(), 3, 4);
        Category category = new Category();
        category.setId(1L);
        category.setCategoryName("主食");
        ReflectionTestUtils.setField(productService, "productConverter",
                Mappers.getMapper(ProductConverter.class));
        when(productMapper.selectOne(any())).thenReturn(product);
        when(categoryMapper.selectById(1L)).thenReturn(category);

        MerchantProductVO detail = productService.getMerchantProductDetail(PRODUCT_ID);

        assertEquals(4, detail.getVersion());
        verifyNoInteractions(productCacheService);
    }

    @Test
    void onShelfMovesProductWithNoStockToSaleOut() {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setMerchantId(MERCHANT_ID);
        product.setCategoryId(1L);
        product.setProductName("暂时缺货商品");
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(0);
        product.setStatus(ProductStatusEnum.OFF_SALE.getCode());
        product.setVersion(0);

        Category category = new Category();
        category.setId(1L);
        when(productMapper.selectOne(any())).thenReturn(product);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(productMapper.update(any(Product.class), any())).thenReturn(1);

        assertDoesNotThrow(() -> productService.onShelf(PRODUCT_ID));
        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), product.getStatus());
        verify(cacheInvalidationTaskService).requestInvalidation(
                RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID
        );
    }

    @Test
    void increaseStockEvictsProductDetailCacheWhenAvailabilityChanges() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(2);
        when(productMapper.increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.increaseStock(PRODUCT_ID, 2);

        verify(productMapper).increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode());
        verify(cacheInvalidationTaskService).requestInvalidation(
                RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID
        );
    }

    @Test
    void increaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(7);
        when(productMapper.increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.increaseStock(PRODUCT_ID, 2);

        verify(cacheInvalidationTaskService, never()).requestInvalidation(anyString());
    }

    @Test
    void merchantStockResetMovesOnSaleProductToSaleOut() {
        UpdateProductDTO dto = new UpdateProductDTO();
        dto.setStock(0);
        dto.setVersion(4);

        Product currentProduct = product(ProductStatusEnum.ON_SALE.getCode(), 6, 4);
        Product updateEntity = new Product();
        updateEntity.setStock(0);
        updateEntity.setVersion(4);
        Product updatedProduct = product(ProductStatusEnum.SALE_OUT.getCode(), 0, 5);

        when(productConverter.toProduct(dto)).thenReturn(updateEntity);
        when(productMapper.selectOne(any())).thenReturn(currentProduct, updatedProduct);
        when(productMapper.update(any(Product.class), any())).thenReturn(1);
        when(categoryMapper.selectById(1L)).thenReturn(new Category());

        productService.updateProduct(PRODUCT_ID, dto);

        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), updateEntity.getStatus());
        verify(cacheInvalidationTaskService).requestInvalidation(
                RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID
        );
    }

    @Test
    void cacheHitReturnsCachedAvailabilityWithoutQueryingDatabase() throws Exception {
        ProductDetailCacheDTO cachedProduct = new ProductDetailCacheDTO();
        cachedProduct.setId(PRODUCT_ID);
        cachedProduct.setMerchantId(MERCHANT_ID);
        cachedProduct.setStatus(ProductStatusEnum.ON_SALE.getCode());
        cachedProduct.setInStock(true);

        when(productCacheService.get(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID))
                .thenReturn("cached-product");
        when(objectMapper.readValue("cached-product", ProductDetailCacheDTO.class)).thenReturn(cachedProduct);
        when(productConverter.toProductVO(cachedProduct)).thenAnswer(invocation -> {
            ProductDetailCacheDTO source = invocation.getArgument(0);
            ProductVO result = new ProductVO();
            result.setStatus(source.getStatus());
            result.setInStock(source.getInStock());
            return result;
        });

        ProductVO result = productService.getProductDetail(PRODUCT_ID);

        assertEquals(ProductStatusEnum.ON_SALE.getCode(), result.getStatus());
        assertEquals(Boolean.TRUE, result.getInStock());
        verifyNoInteractions(productMapper);
    }

    @Test
    void missingProductCachesNullMarkerAndSkipsSecondDatabaseQuery() {
        String key = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
        String lockKey = "Lock:" + key;
        when(productCacheService.get(key)).thenReturn(null, null, NULL_PRODUCT_CACHE);
        when(productCacheService.tryLock(
                anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> productService.getProductDetail(PRODUCT_ID));
        assertThrows(BusinessException.class,
                () -> productService.getProductDetail(PRODUCT_ID));

        verify(productMapper, times(1)).selectById(PRODUCT_ID);
        verify(productCacheService).set(
                eq(key),
                eq(NULL_PRODUCT_CACHE),
                anyLong(),
                eq(TimeUnit.MINUTES)
        );
        ArgumentCaptor<String> lockTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(productCacheService).tryLock(
                eq(lockKey),
                lockTokenCaptor.capture(),
                eq(10L),
                eq(TimeUnit.SECONDS)
        );
        verify(productCacheService).unlock(lockKey, lockTokenCaptor.getValue());
    }

    @Test
    void cacheMissSerializesDatabaseProductAndCachesJson() throws Exception {
        String key = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
        Product product = product(ProductStatusEnum.ON_SALE.getCode(), 8, 1);
        ProductDetailCacheDTO cacheDto = new ProductDetailCacheDTO();
        cacheDto.setId(PRODUCT_ID);
        cacheDto.setMerchantId(MERCHANT_ID);
        cacheDto.setInStock(true);
        ProductVO productVO = new ProductVO();

        when(productCacheService.get(key)).thenReturn(null);
        when(productCacheService.tryLock(
                anyString(), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        when(productConverter.toProductDetailCacheDTO(product)).thenReturn(cacheDto);
        when(objectMapper.writeValueAsString(cacheDto)).thenReturn("product-json");
        when(productConverter.toProductVO(cacheDto)).thenReturn(productVO);

        assertEquals(productVO, productService.getProductDetail(PRODUCT_ID));

        verify(objectMapper).writeValueAsString(cacheDto);
        verify(productCacheService).set(
                eq(key),
                eq("product-json"),
                anyLong(),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void cacheWriteFailureStillReturnsLoadedProductWithoutSecondDatabaseQuery() throws Exception {
        String key = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
        Product product = product(ProductStatusEnum.ON_SALE.getCode(), 8, 1);
        ProductDetailCacheDTO cacheDto = new ProductDetailCacheDTO();
        cacheDto.setId(PRODUCT_ID);
        cacheDto.setMerchantId(MERCHANT_ID);
        cacheDto.setInStock(true);
        ProductVO productVO = new ProductVO();

        useProductCacheWithFailingWriter(key, "product-json");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        when(productConverter.toProductDetailCacheDTO(product)).thenReturn(cacheDto);
        when(objectMapper.writeValueAsString(cacheDto)).thenReturn("product-json");
        when(productConverter.toProductVO(cacheDto)).thenReturn(productVO);

        assertEquals(productVO, productService.getProductDetail(PRODUCT_ID));

        verify(productMapper, times(1)).selectById(PRODUCT_ID);
        verify(valueOperations).set(
                eq(key),
                eq("product-json"),
                anyLong(),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void nullMarkerWriteFailureStillReportsMissingProductWithoutSecondDatabaseQuery() {
        String key = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
        useProductCacheWithFailingWriter(key, NULL_PRODUCT_CACHE);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.getProductDetail(PRODUCT_ID)
        );

        assertEquals("商品不存在", exception.getMessage());
        verify(productMapper, times(1)).selectById(PRODUCT_ID);
        verify(valueOperations).set(
                eq(key),
                eq(NULL_PRODUCT_CACHE),
                anyLong(),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void cacheMissRetriesLockAndRebuildsAfterAcquiringIt() throws Exception {
        String key = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
        String lockKey = "Lock:" + key;
        Product product = product(ProductStatusEnum.ON_SALE.getCode(), 8, 1);
        ProductDetailCacheDTO cacheDto = new ProductDetailCacheDTO();
        cacheDto.setId(PRODUCT_ID);
        cacheDto.setMerchantId(MERCHANT_ID);
        cacheDto.setInStock(true);
        ProductVO productVO = new ProductVO();

        // 首次读取、等待后的读取、重试抢锁后的二次确认都没有缓存。
        when(productCacheService.get(key)).thenReturn(null);
        when(productCacheService.tryLock(
                eq(lockKey), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false, true);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        when(productConverter.toProductDetailCacheDTO(product)).thenReturn(cacheDto);
        when(objectMapper.writeValueAsString(cacheDto)).thenReturn("product-json");
        when(productConverter.toProductVO(cacheDto)).thenReturn(productVO);

        assertEquals(productVO, productService.getProductDetail(PRODUCT_ID));

        ArgumentCaptor<String> lockTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(productCacheService, times(2)).tryLock(
                eq(lockKey),
                lockTokenCaptor.capture(),
                eq(10L),
                eq(TimeUnit.SECONDS)
        );
        assertEquals(
                lockTokenCaptor.getAllValues().get(0),
                lockTokenCaptor.getAllValues().get(1)
        );
        verify(productCacheService, times(3)).get(key);
        verify(productMapper, times(1)).selectById(PRODUCT_ID);
        verify(productCacheService).set(
                eq(key),
                eq("product-json"),
                anyLong(),
                eq(TimeUnit.MINUTES)
        );
        verify(productCacheService).unlock(
                lockKey,
                lockTokenCaptor.getAllValues().get(1)
        );
    }

    @Test
    void cacheMissTimesOutWithoutFallingBackToDatabase() {
        String key = RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID;
        String lockKey = "Lock:" + key;
        when(productCacheService.get(key)).thenReturn(null);
        when(productCacheService.tryLock(
                eq(lockKey), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.getProductDetail(PRODUCT_ID)
        );

        assertEquals("请求超时，请稍后重试", exception.getMessage());
        // 首次尝试一次，加上循环中的五次重试。
        verify(productCacheService, times(6)).get(key);
        verify(productCacheService, times(6)).tryLock(
                eq(lockKey),
                anyString(),
                eq(10L),
                eq(TimeUnit.SECONDS)
        );
        verify(productCacheService, never()).unlock(anyString(), anyString());
        verifyNoInteractions(productMapper);
    }

    @Test
    void decreaseStockEvictsProductDetailCacheWhenAvailabilityChanges() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(0);
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.decreaseStock(PRODUCT_ID, 1);

        verify(cacheInvalidationTaskService).requestInvalidation(
                RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID
        );
    }

    @Test
    void decreaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(4);
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.decreaseStock(PRODUCT_ID, 1);

        verify(cacheInvalidationTaskService, never()).requestInvalidation(anyString());
    }

    @Test
    void restoreProductRestoresOffSaleAndRequestsCacheInvalidation() {
        when(productMapper.restoreDeletedProduct(
                PRODUCT_ID,
                MERCHANT_ID,
                ProductStatusEnum.OFF_SALE.getCode()
        )).thenReturn(1);

        productService.restoreProduct(PRODUCT_ID);

        verify(productMapper).restoreDeletedProduct(
                PRODUCT_ID,
                MERCHANT_ID,
                ProductStatusEnum.OFF_SALE.getCode()
        );
        verify(cacheInvalidationTaskService).requestInvalidation(
                RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID
        );
    }

    @Test
    void restoreProductReportsDuplicateActiveNameAsBusinessError() {
        doThrow(new DuplicateKeyException("duplicate active product name"))
                .when(productMapper)
                .restoreDeletedProduct(
                        PRODUCT_ID,
                        MERCHANT_ID,
                        ProductStatusEnum.OFF_SALE.getCode()
                );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.restoreProduct(PRODUCT_ID)
        );

        assertEquals("当前店铺已存在同名商品", exception.getMessage());
        verify(cacheInvalidationTaskService, never()).requestInvalidation(anyString());
    }

    @Test
    void restoreProductRejectsMissingActiveOrForeignProduct() {
        when(productMapper.restoreDeletedProduct(
                PRODUCT_ID,
                MERCHANT_ID,
                ProductStatusEnum.OFF_SALE.getCode()
        )).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.restoreProduct(PRODUCT_ID)
        );

        assertEquals("商品不存在、未删除或不属于当前商家", exception.getMessage());
        verify(cacheInvalidationTaskService, never()).requestInvalidation(anyString());
    }

    private void useProductCacheWithFailingWriter(String cacheKey, String cacheValue) {
        String lockKey = "Lock:" + cacheKey;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq(lockKey),
                anyString(),
                eq(10L),
                eq(TimeUnit.SECONDS)
        )).thenReturn(true);
        doThrow(new IllegalStateException("Redis write failed"))
                .when(valueOperations)
                .set(
                        eq(cacheKey),
                        eq(cacheValue),
                        anyLong(),
                        eq(TimeUnit.MINUTES)
                );
        ReflectionTestUtils.setField(
                productService,
                "productCacheService",
                new ProductCacheService(redisTemplate)
        );
    }

    private Product product(Integer status, Integer stock, Integer version) {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setMerchantId(MERCHANT_ID);
        product.setCategoryId(1L);
        product.setProductName("测试商品");
        product.setPrice(BigDecimal.TEN);
        product.setStatus(status);
        product.setStock(stock);
        product.setVersion(version);
        return product;
    }
}
