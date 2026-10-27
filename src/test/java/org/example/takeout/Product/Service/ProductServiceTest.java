package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Common.Constants.DeleteConstant;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Product.class);
    }

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
        PageHelper.clearPage();
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
        verifyNoInteractions(productDetailCacheService);
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
            assertEquals(ProductStatusEnum.OFF_SALE.getCode(), product.getStatus());
            assertEquals(DeleteConstant.NOT_DELETED, product.getIsDeleted());
            assertEquals(0, product.getStockVersion());
            assertEquals(ProductService.DEFAULT_PRODUCT_IMAGE_URL, product.getImageUrl());
            product.setId(PRODUCT_ID);
            return 1;
        });

        MerchantProductVO created = productService.createProduct(dto);

        assertEquals(PRODUCT_ID, created.getId());
        assertEquals(0, created.getVersion());
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void createProductPropagatesInvalidationRequestFailureBeforeReturningResult() {
        Category category = new Category();
        category.setId(1L);
        CreateProductDTO dto = new CreateProductDTO();
        dto.setCategoryId(category.getId());
        Product product = new Product();
        when(categoryService.lockActiveCategory(category.getId(), MERCHANT_ID)).thenReturn(category);
        when(productConverter.toProduct(dto, MERCHANT_ID)).thenReturn(product);
        when(productMapper.insert(product)).thenAnswer(invocation -> {
            product.setId(PRODUCT_ID);
            return 1;
        });
        IllegalStateException failure = new IllegalStateException("失效任务写入失败");
        doThrow(failure).when(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> productService.createProduct(dto)));

        verify(productMapper).insert(product);
        verify(productConverter, never()).toMerchantProductVO(any(), any());
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
    void merchantStockResetMovesOnSaleProductToSaleOutAndAdvancesStockVersion() {
        UpdateProductDTO dto = new UpdateProductDTO();
        dto.setStock(0);
        dto.setVersion(4);

        Product currentProduct = product(ProductStatusEnum.ON_SALE.getCode(), 6, 4, 2);
        Product updateEntity = new Product();
        updateEntity.setStock(0);
        updateEntity.setVersion(4);
        Product updatedProduct = product(ProductStatusEnum.SALE_OUT.getCode(), 0, 5, 3);

        when(productConverter.toProduct(dto)).thenReturn(updateEntity);
        when(productMapper.selectOne(any())).thenReturn(currentProduct, updatedProduct);
        when(productMapper.update(any(Product.class), any())).thenReturn(1);
        when(categoryService.getCategory(1L)).thenReturn(new Category());

        productService.updateProduct(PRODUCT_ID, dto);

        assertEquals(ProductStatusEnum.SALE_OUT.getCode(), updateEntity.getStatus());
        verify(productMapper).update(any(Product.class), wrapperWithStockVersionIncrement());
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void merchantStockResetAdvancesStockVersionEvenWhenValueIsUnchanged() {
        UpdateProductDTO dto = new UpdateProductDTO();
        dto.setStock(6);
        dto.setVersion(4);

        Product currentProduct = product(ProductStatusEnum.ON_SALE.getCode(), 6, 4, 2);
        Product updateEntity = new Product();
        updateEntity.setStock(6);
        updateEntity.setVersion(4);
        Product updatedProduct = product(ProductStatusEnum.ON_SALE.getCode(), 6, 5, 3);

        when(productConverter.toProduct(dto)).thenReturn(updateEntity);
        when(productMapper.selectOne(any())).thenReturn(currentProduct, updatedProduct);
        when(productMapper.update(any(Product.class), any())).thenReturn(1);
        when(categoryService.getCategory(1L)).thenReturn(new Category());

        productService.updateProduct(PRODUCT_ID, dto);

        verify(productMapper).update(any(Product.class), wrapperWithStockVersionIncrement());
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void merchantNonStockUpdateDoesNotAdvanceStockVersion() {
        UpdateProductDTO dto = new UpdateProductDTO();
        dto.setProductName("只改名称");
        dto.setPrice(new BigDecimal("12.34"));
        dto.setVersion(4);

        Product currentProduct = product(ProductStatusEnum.ON_SALE.getCode(), 6, 4, 2);
        Product updateEntity = new Product();
        updateEntity.setProductName(dto.getProductName());
        updateEntity.setPrice(dto.getPrice());
        updateEntity.setVersion(4);
        Product updatedProduct = product(ProductStatusEnum.ON_SALE.getCode(), 6, 5, 2);
        updatedProduct.setProductName(dto.getProductName());

        when(productConverter.toProduct(dto)).thenReturn(updateEntity);
        when(productMapper.selectOne(any())).thenReturn(currentProduct, updatedProduct);
        when(productMapper.update(any(Product.class), any())).thenReturn(1);
        when(categoryService.getCategory(1L)).thenReturn(new Category());

        productService.updateProduct(PRODUCT_ID, dto);

        verify(productMapper).update(any(Product.class), wrapperWithoutStockVersionIncrement());
        verify(productDetailCacheService).evictProductDetailCache(PRODUCT_ID);
    }

    @Test
    void listProductsFiltersByKeywordAndPreservesMerchantVisibilityAndVoConversion() {
        Product product = product(ProductStatusEnum.ON_SALE.getCode(), 4, 2);
        Category category = new Category();
        category.setId(1L);
        category.setCategoryName("便当");
        MerchantProductVO expected = new MerchantProductVO();
        expected.setId(PRODUCT_ID);
        expected.setProductName("鸡腿便当");
        when(productMapper.selectList(any())).thenReturn(List.of(product));
        when(categoryService.getCategoryMap(List.of(1L), MERCHANT_ID))
                .thenReturn(Map.of(1L, category));
        when(productConverter.toMerchantProductVO(product, category)).thenReturn(expected);

        PageInfo<MerchantProductVO> result =
                productService.listProducts(1, 10, null, null, "便当");

        assertEquals(List.of(expected), result.getList());
        verify(productConverter).toMerchantProductVO(product, category);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Product>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(productMapper).selectList(wrapperCaptor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        String sqlSegment = wrapper.getSqlSegment().toUpperCase();
        assertTrue(sqlSegment.contains("PRODUCT_NAME LIKE"));
        assertTrue(sqlSegment.contains("ORDER BY ID DESC"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(MERCHANT_ID));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(DeleteConstant.NOT_DELETED));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%便当%"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void listProductsOmitsKeywordFilterForNullOrBlankKeyword(String keyword) {
        Product product = product(ProductStatusEnum.ON_SALE.getCode(), 4, 2);
        when(productMapper.selectList(any())).thenReturn(List.of(product));
        when(categoryService.getCategoryMap(List.of(1L), MERCHANT_ID)).thenReturn(Map.of());

        productService.listProducts(1, 10, null, null, keyword);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Product>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(productMapper).selectList(wrapperCaptor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        assertFalse(wrapper.getSqlSegment().toUpperCase().contains("PRODUCT_NAME LIKE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(MERCHANT_ID));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(DeleteConstant.NOT_DELETED));
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
    void getProductDetailReturnsProductOwnedByAnotherMerchantWithoutMerchantContext() {
        ProductDetailCacheDTO cachedProduct = new ProductDetailCacheDTO();
        cachedProduct.setId(PRODUCT_ID);
        cachedProduct.setMerchantId(MERCHANT_ID + 1);
        cachedProduct.setProductName("公共字段商品");
        cachedProduct.setPrice(new BigDecimal("15.50"));
        cachedProduct.setInStock(true);
        cachedProduct.setStatus(ProductStatusEnum.ON_SALE.getCode());
        ProductVO expected = new ProductVO();
        expected.setId(PRODUCT_ID);
        expected.setProductName("公共字段商品");
        expected.setPrice(new BigDecimal("15.50"));
        expected.setInStock(true);
        expected.setStatus(ProductStatusEnum.ON_SALE.getCode());

        when(productDetailCacheService.getProductDetailCache(eq(PRODUCT_ID), any()))
                .thenReturn(cachedProduct);
        when(productConverter.toProductVO(cachedProduct)).thenReturn(expected);
        MerchantContextHolder.clear();

        ProductVO result = productService.getProductDetail(PRODUCT_ID);

        assertSame(expected, result);
        verify(productDetailCacheService).getProductDetailCache(eq(PRODUCT_ID), any());
        verify(productConverter).toProductVO(cachedProduct);
    }

    @Test
    void getProductDetailSuppliesMysqlLoaderToTheCacheService() {
        Product product = product(ProductStatusEnum.ON_SALE.getCode(), 8, 1);
        ProductDetailCacheDTO loaded = new ProductDetailCacheDTO();
        loaded.setId(PRODUCT_ID);
        loaded.setMerchantId(MERCHANT_ID);
        ProductVO expected = new ProductVO();
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        when(productConverter.toProductDetailCacheDTO(product)).thenReturn(loaded);
        when(productDetailCacheService.getProductDetailCache(eq(PRODUCT_ID), any()))
                .thenAnswer(invocation -> {
                    Supplier<ProductDetailCacheDTO> loader = invocation.getArgument(1);
                    return loader.get();
                });
        when(productConverter.toProductVO(loaded)).thenReturn(expected);

        assertSame(expected, productService.getProductDetail(PRODUCT_ID));

        verify(productMapper).selectById(PRODUCT_ID);
        verify(productConverter).toProductDetailCacheDTO(product);
    }

    @Test
    void getProductDetailRejectsMissingProductReturnedByCacheLoader() {
        when(productDetailCacheService.getProductDetailCache(eq(PRODUCT_ID), any()))
                .thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> productService.getProductDetail(PRODUCT_ID));

        assertEquals("商品不存在", exception.getMessage());
        verifyNoInteractions(productMapper, productConverter);
    }

    @Test
    void updateProductRejectsUnavailableDestinationCategoryBeforeWriting() {
        UpdateProductDTO dto = new UpdateProductDTO();
        dto.setCategoryId(2L);
        dto.setVersion(4);
        when(categoryService.lockActiveCategory(2L, MERCHANT_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> productService.updateProduct(PRODUCT_ID, dto));

        assertEquals("分类不存在或不可用", exception.getMessage());
        verify(categoryService).lockActiveCategory(2L, MERCHANT_ID);
        verifyNoInteractions(productMapper, productConverter, productDetailCacheService);
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
        return product(status, stock, version, 0);
    }

    private Product product(Integer status, Integer stock, Integer version, Integer stockVersion) {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setMerchantId(MERCHANT_ID);
        product.setCategoryId(1L);
        product.setProductName("测试商品");
        product.setPrice(BigDecimal.TEN);
        product.setStatus(status);
        product.setStock(stock);
        product.setVersion(version);
        product.setStockVersion(stockVersion);
        return product;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Wrapper<Product> wrapperWithStockVersionIncrement() {
        return argThat(wrapper -> {
            String sqlSet = ((AbstractWrapper<?, ?, ?>) wrapper).getSqlSet();
            return sqlSet != null && sqlSet.contains("stock_version = stock_version + 1");
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Wrapper<Product> wrapperWithoutStockVersionIncrement() {
        return argThat(wrapper -> {
            String sqlSet = ((AbstractWrapper<?, ?, ?>) wrapper).getSqlSet();
            return sqlSet == null || !sqlSet.contains("stock_version = stock_version + 1");
        });
    }
}
