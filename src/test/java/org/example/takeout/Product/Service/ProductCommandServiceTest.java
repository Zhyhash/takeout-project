package org.example.takeout.Product.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductCommandServiceTest {

    private static final Long PRODUCT_ID = 301L;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductDetailCacheService productDetailCacheService;

    @InjectMocks
    private ProductCommandService productCommandService;

    @Test
    void decreaseStockEvictsProductDetailCacheWhenAvailabilityChanges() {
        Product updatedProduct = productWithStock(0);
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID)).thenReturn(updatedProduct);

        productCommandService.decreaseStock(PRODUCT_ID, 1);

        verify(productMapper).update(isNull(), any());
        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void decreaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        when(productMapper.update(isNull(), any())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(4));

        productCommandService.decreaseStock(PRODUCT_ID, 1);

        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService, never()).evictProductDetailCache(any());
    }

    @Test
    void increaseStockEvictsProductDetailCacheWhenAvailabilityChanges() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(2));

        productCommandService.increaseStock(PRODUCT_ID, 2);

        verify(productMapper).increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode());
        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void increaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(7));

        productCommandService.increaseStock(PRODUCT_ID, 2);

        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService, never()).evictProductDetailCache(any());
    }

    @Test
    void increaseStockReadsDeletedProductStockToPreserveOrderReturnBehavior() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(2));

        productCommandService.increaseStock(PRODUCT_ID, 2);

        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
    }

    @Test
    void failedStockUpdateDoesNotReadBackStockOrEvictCache() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> productCommandService.increaseStock(PRODUCT_ID, 2));

        verify(productMapper, never()).selectStockByIdIncludingDeleted(any());
        verifyNoInteractions(productDetailCacheService);
    }

    @Test
    void migrateProductsEvictsEveryMovedProductDetail() {
        when(productMapper.selectIdsByMerchantIdAndCategoryId(10L, 20L))
                .thenReturn(List.of(101L, 102L));
        when(productMapper.updateCategory(10L, 20L, 30L)).thenReturn(2);

        productCommandService.migrateProductsToCategory(10L, 20L, 30L);

        verify(productDetailCacheService).evictProductDetailCache(101L);
        verify(productDetailCacheService).evictProductDetailCache(102L);
    }

    @Test
    void migrateProductsSkipsUpdateAndInvalidationWhenCategoryIsEmpty() {
        when(productMapper.selectIdsByMerchantIdAndCategoryId(10L, 20L))
                .thenReturn(List.of());

        productCommandService.migrateProductsToCategory(10L, 20L, 30L);

        verify(productMapper, never()).updateCategory(any(), any(), any());
        verifyNoInteractions(productDetailCacheService);
    }

    @Test
    void migrateProductsRejectsUnexpectedUpdateCountWithoutInvalidating() {
        when(productMapper.selectIdsByMerchantIdAndCategoryId(10L, 20L))
                .thenReturn(List.of(101L, 102L));
        when(productMapper.updateCategory(10L, 20L, 30L)).thenReturn(1);

        assertThrows(BusinessException.class,
                () -> productCommandService.migrateProductsToCategory(10L, 20L, 30L));

        verifyNoInteractions(productDetailCacheService);
    }

    private Product productWithStock(Integer stock) {
        Product product = new Product();
        product.setStock(stock);
        return product;
    }
}
