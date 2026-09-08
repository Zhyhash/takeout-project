package org.example.takeout.Product.Service;

import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Cache.ProductCacheService;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.ProductVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    void deleteProductLogicallyDeletesOwnedProductAndEvictsCache() {
        when(productMapper.delete(any())).thenReturn(1);

        productService.deleteProduct(PRODUCT_ID);

        verify(productMapper).delete(any());
        verify(productCacheService).delete(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID);
    }

    @Test
    void deleteProductRejectsMissingDeletedOrForeignProduct() {
        when(productMapper.delete(any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> productService.deleteProduct(PRODUCT_ID));

        verify(productCacheService, never()).delete(any(String.class));
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
        verify(productCacheService).delete(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID);
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

        verify(productCacheService, never()).delete(any(String.class));
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
    void decreaseStockEvictsProductDetailCacheWhenAvailabilityChanges() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(0);
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.decreaseStock(PRODUCT_ID, 1);

        verify(productCacheService).delete(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID);
    }

    @Test
    void decreaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(4);
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.decreaseStock(PRODUCT_ID, 1);

        verify(productCacheService, never()).delete(any(String.class));
    }

    @Test
    void restoreProductForcesOffSaleStatus() {
        when(productMapper.restoreDeletedProduct(
                PRODUCT_ID,
                MERCHANT_ID,
                ProductStatusEnum.OFF_SALE.getCode())).thenReturn(1);

        productService.restoreProduct(PRODUCT_ID);

        verify(productMapper).restoreDeletedProduct(
                PRODUCT_ID,
                MERCHANT_ID,
                ProductStatusEnum.OFF_SALE.getCode());
        verify(productCacheService).delete(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID);
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
