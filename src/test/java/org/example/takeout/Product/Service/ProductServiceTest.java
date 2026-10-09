package org.example.takeout.Product.Service;

import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Long MERCHANT_ID = 201L;
    private static final Long PRODUCT_ID = 301L;
    @Mock
    private ProductMapper productMapper;

    @Mock
    private CategoryService categoryService;

    @Mock
    private ProductConverter productConverter;

    @Mock
    private ProductDetailCacheService productDetailCacheService;

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
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void deleteProductRejectsMissingDeletedOrForeignProduct() {
        when(productMapper.delete(any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> productService.deleteProduct(PRODUCT_ID));

        verifyNoInteractions(productDetailCacheService);
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

        when(categoryService.lockActiveCategory(category.getId(), MERCHANT_ID)).thenReturn(category);
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
        when(categoryService.lockActiveCategory(1L, MERCHANT_ID)).thenReturn(category);
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
        when(categoryService.getCategory(1L)).thenReturn(category);

        MerchantProductVO detail = productService.getMerchantProductDetail(PRODUCT_ID);

        assertEquals(4, detail.getVersion());
        verifyNoInteractions(productDetailCacheService);
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
        when(categoryService.getCategory(1L)).thenReturn(category);
        when(productMapper.update(any(Product.class), any())).thenReturn(1);

        assertDoesNotThrow(() -> productService.onShelf(PRODUCT_ID));
        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), product.getStatus());
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
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
        when(categoryService.getCategory(1L)).thenReturn(new Category());

        productService.updateProduct(PRODUCT_ID, dto);

        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), updateEntity.getStatus());
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void getProductDetailUsesCacheServiceAndConvertsTheReturnedDetail() {
        ProductDetailCacheDTO cachedProduct = new ProductDetailCacheDTO();
        cachedProduct.setId(PRODUCT_ID);
        cachedProduct.setMerchantId(MERCHANT_ID);
        cachedProduct.setStatus(ProductStatusEnum.ON_SALE.getCode());
        cachedProduct.setInStock(true);

        when(productDetailCacheService.getProductDetailCache(eq(PRODUCT_ID), any()))
                .thenReturn(cachedProduct);
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
        verify(productDetailCacheService).getProductDetailCache(eq(PRODUCT_ID), any());
        verifyNoInteractions(productMapper);
    }

    @Test
    void getProductDetailRejectsCachedProductOwnedByAnotherMerchant() {
        ProductDetailCacheDTO cachedProduct = new ProductDetailCacheDTO();
        cachedProduct.setId(PRODUCT_ID);
        cachedProduct.setMerchantId(MERCHANT_ID + 1);
        cachedProduct.setInStock(true);
        when(productDetailCacheService.getProductDetailCache(eq(PRODUCT_ID), any()))
                .thenReturn(cachedProduct);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> productService.getProductDetail(PRODUCT_ID)
        );

        assertEquals("商品不存在或不属于当前商家", exception.getMessage());
        verify(productConverter, never()).toProductVO(any(ProductDetailCacheDTO.class));
    }

    @Test
    void getProductDetailFallsBackToMysqlWhenCacheIsUnavailable() {
        Product product = product(ProductStatusEnum.ON_SALE.getCode(), 8, 1);
        ProductDetailCacheDTO cacheDto = new ProductDetailCacheDTO();
        cacheDto.setId(PRODUCT_ID);
        cacheDto.setMerchantId(MERCHANT_ID);
        cacheDto.setInStock(true);
        ProductVO productVO = new ProductVO();

        when(productDetailCacheService.getProductDetailCache(eq(PRODUCT_ID), any()))
                .thenThrow(new RedisCacheUnavailableException("Redis unavailable", new IllegalStateException()));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        when(productConverter.toProductDetailCacheDTO(product)).thenReturn(cacheDto);
        when(productConverter.toProductVO(cacheDto)).thenReturn(productVO);

        assertEquals(productVO, productService.getProductDetail(PRODUCT_ID));

        verify(productMapper).selectById(PRODUCT_ID);
        verify(productConverter).toProductDetailCacheDTO(product);
        verify(productDetailCacheService).getProductDetailCache(eq(PRODUCT_ID), any());
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
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
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
        verify(productDetailCacheService, never()).evictProductDetailCache(anyLong());
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
        verify(productDetailCacheService, never()).evictProductDetailCache(anyLong());
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
