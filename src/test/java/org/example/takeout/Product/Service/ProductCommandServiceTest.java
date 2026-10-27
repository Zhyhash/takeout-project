package org.example.takeout.Product.Service;

import org.example.takeout.Common.Constants.DeleteConstant;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductCommandServiceTest {

    private static final Long PRODUCT_ID = 301L;
    private static final Integer STOCK_VERSION = 3;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductDetailCacheService productDetailCacheService;

    @InjectMocks
    private ProductCommandService productCommandService;

    @Test
    void decreaseStockReturnsDeductedBaselineVersionAndEvictsCacheWhenAvailabilityChanges() {
        when(productMapper.selectStockByIdForUpdate(PRODUCT_ID))
                .thenReturn(productWithStock(1, STOCK_VERSION));
        when(productMapper.decreaseStock(
                PRODUCT_ID, 1, STOCK_VERSION,
                DeleteConstant.NOT_DELETED,
                ProductStatusEnum.ON_SALE.getCode(),
                ProductStatusEnum.SALE_OUT.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(0, STOCK_VERSION));

        assertEquals(STOCK_VERSION, productCommandService.decreaseStock(PRODUCT_ID, 1));

        verify(productMapper).selectStockByIdForUpdate(PRODUCT_ID);
        verify(productMapper).decreaseStock(
                PRODUCT_ID, 1, STOCK_VERSION,
                DeleteConstant.NOT_DELETED,
                ProductStatusEnum.ON_SALE.getCode(),
                ProductStatusEnum.SALE_OUT.getCode());
        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void decreaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        when(productMapper.selectStockByIdForUpdate(PRODUCT_ID))
                .thenReturn(productWithStock(5, STOCK_VERSION));
        when(productMapper.decreaseStock(
                PRODUCT_ID, 1, STOCK_VERSION,
                DeleteConstant.NOT_DELETED,
                ProductStatusEnum.ON_SALE.getCode(),
                ProductStatusEnum.SALE_OUT.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(4, STOCK_VERSION));

        assertEquals(STOCK_VERSION, productCommandService.decreaseStock(PRODUCT_ID, 1));

        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService, never()).evictProductDetailCache(any());
    }

    @Test
    void decreaseStockFailsWithoutReturningVersionWhenConditionalUpdateMisses() {
        when(productMapper.selectStockByIdForUpdate(PRODUCT_ID))
                .thenReturn(productWithStock(5, STOCK_VERSION));
        when(productMapper.decreaseStock(
                PRODUCT_ID, 1, STOCK_VERSION,
                DeleteConstant.NOT_DELETED,
                ProductStatusEnum.ON_SALE.getCode(),
                ProductStatusEnum.SALE_OUT.getCode())).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> productCommandService.decreaseStock(PRODUCT_ID, 1));

        verify(productMapper, never()).selectStockByIdIncludingDeleted(any());
        verifyNoInteractions(productDetailCacheService);
    }

    @Test
    void decreaseStockFailsWhenProductRowIsMissing() {
        when(productMapper.selectStockByIdForUpdate(PRODUCT_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> productCommandService.decreaseStock(PRODUCT_ID, 1));

        verify(productMapper, never()).decreaseStock(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(productDetailCacheService);
    }

    @Test
    void increaseStockOnlyRestoresMatchingBaselineVersion() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2, STOCK_VERSION,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(2, STOCK_VERSION));

        assertTrue(productCommandService.increaseStock(PRODUCT_ID, 2, STOCK_VERSION));

        verify(productMapper).increaseStock(
                PRODUCT_ID, 2, STOCK_VERSION,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode());
        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void increaseStockSkipsStaleVersionWithoutReadingOrInvalidating() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2, STOCK_VERSION,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(0);

        assertFalse(productCommandService.increaseStock(PRODUCT_ID, 2, STOCK_VERSION));

        verify(productMapper, never()).selectStockByIdIncludingDeleted(any());
        verifyNoInteractions(productDetailCacheService);
    }

    @Test
    void increaseStockSkipsWhenOrderItemHasNoStockVersion() {
        assertFalse(productCommandService.increaseStock(PRODUCT_ID, 2, null));

        verifyNoInteractions(productMapper, productDetailCacheService);
    }

    @Test
    void increaseStockKeepsProductDetailCacheWhenAvailabilityDoesNotChange() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2, STOCK_VERSION,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(7, STOCK_VERSION));

        assertTrue(productCommandService.increaseStock(PRODUCT_ID, 2, STOCK_VERSION));

        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
        verify(productDetailCacheService, never()).evictProductDetailCache(any());
    }

    @Test
    void increaseStockReadsDeletedProductStockToPreserveOrderReturnBehavior() {
        when(productMapper.increaseStock(
                PRODUCT_ID, 2, STOCK_VERSION,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode())).thenReturn(1);
        when(productMapper.selectStockByIdIncludingDeleted(PRODUCT_ID))
                .thenReturn(productWithStock(2, STOCK_VERSION));

        assertTrue(productCommandService.increaseStock(PRODUCT_ID, 2, STOCK_VERSION));

        verify(productMapper).selectStockByIdIncludingDeleted(PRODUCT_ID);
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

    private Product productWithStock(Integer stock, Integer stockVersion) {
        Product product = new Product();
        product.setStock(stock);
        product.setStockVersion(stockVersion);
        return product;
    }
}
