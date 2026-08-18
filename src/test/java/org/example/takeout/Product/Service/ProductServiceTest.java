package org.example.takeout.Product.Service;

import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Long MERCHANT_ID = 201L;
    private static final Long PRODUCT_ID = 301L;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private ProductConverter productConverter;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

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
        verify(stringRedisTemplate).delete(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID);
    }

    @Test
    void deleteProductRejectsMissingDeletedOrForeignProduct() {
        when(productMapper.delete(any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> productService.deleteProduct(PRODUCT_ID));

        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    @Test
    void onShelfAllowsProductWithNoStock() {
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
        assertEquals(ProductStatusEnum.ON_SALE.getCode(), product.getStatus());
    }

    @Test
    void increaseStockEvictsProductDetailCacheWhenAvailabilityChanges() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(2);
        when(productMapper.increaseStock(PRODUCT_ID, 2)).thenReturn(1);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.increaseStock(PRODUCT_ID, 2);

        verify(productMapper).increaseStock(PRODUCT_ID, 2);
        verify(stringRedisTemplate).delete(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID);
    }

    @Test
    void increaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(7);
        when(productMapper.increaseStock(PRODUCT_ID, 2)).thenReturn(1);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.increaseStock(PRODUCT_ID, 2);

        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    @Test
    void decreaseStockEvictsProductDetailCacheWhenAvailabilityChanges() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(0);
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.decreaseStock(PRODUCT_ID, 1);

        verify(stringRedisTemplate).delete(RedisKeyConstant.PRODUCT_DETAIL + PRODUCT_ID);
    }

    @Test
    void decreaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        Product updatedProduct = new Product();
        updatedProduct.setStock(4);
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(updatedProduct);

        productService.decreaseStock(PRODUCT_ID, 1);

        verify(stringRedisTemplate, never()).delete(any(String.class));
    }
}
