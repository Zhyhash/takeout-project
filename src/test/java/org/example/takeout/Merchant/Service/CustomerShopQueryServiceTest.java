package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.MerchantDetailVO;
import org.example.takeout.Merchant.VO.MerchantListVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Service.ProductQueryService;
import org.example.takeout.Product.VO.ProductVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerShopQueryServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Merchant.class);
    }

    @Mock
    private MerchantMapper merchantMapper;

    @Mock
    private ProductQueryService productQueryService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private MerchantConverter merchantConverter;

    @InjectMocks
    private CustomerShopQueryService merchantQueryService;

    @AfterEach
    void clearPageHelper() {
        PageHelper.clearPage();
    }

    @Test
    void emptyNameQueriesAllMerchantsWithDefaultStatus() {
        Merchant merchant = new Merchant();
        merchant.setId(201L);
        merchant.setMerchantName("Test Shop");
        merchant.setStatus(MerchantStatusEnum.BUSINESS_OPEN.getCode());

        MerchantListVO shop = new MerchantListVO();
        shop.setId(201L);
        shop.setMerchantName("Test Shop");
        PageInfo<MerchantListVO> expectedPage = new PageInfo<>(List.of(shop));

        when(merchantMapper.selectList(any())).thenReturn(List.of(merchant));
        when(merchantConverter.toPageInfoVO(any())).thenReturn(expectedPage);

        PageInfo<MerchantListVO> result = assertDoesNotThrow(
                () -> merchantQueryService.listMerchants(1, 10, "", null));

        assertSame(expectedPage, result);
        AbstractWrapper<?, ?, ?> wrapper = capturedMerchantWrapper();
        assertFalse(wrapper.getSqlSegment().toUpperCase().contains("LIKE"));
        assertEquals(1, wrapper.getParamNameValuePairs().size());
        assertEquals(MerchantStatusEnum.BUSINESS_OPEN.getCode(),
                wrapper.getParamNameValuePairs().values().iterator().next());
    }

    @Test
    void customerShopDetailRequestsVisibleProductsFromProductQueryService() {
        Merchant merchant = new Merchant();
        merchant.setId(201L);
        merchant.setStatus(MerchantStatusEnum.BUSINESS_OPEN.getCode());
        MerchantDetailVO detail = new MerchantDetailVO();

        when(merchantMapper.selectOne(any())).thenReturn(merchant);
        when(productQueryService.getVisibleProductsByMerchantId(201L)).thenReturn(List.of());
        when(merchantConverter.toMerchantDetailVO(merchant)).thenReturn(detail);

        assertSame(detail, merchantQueryService.getMerchantDetailWithGroupedProducts(201L));

        verify(productQueryService).getVisibleProductsByMerchantId(201L);
    }

    @Test
    void shopDetailBatchesCategoryLookupAndPreservesProductsForUnknownCategory() {
        Merchant merchant = new Merchant();
        MerchantDetailVO detail = new MerchantDetailVO();
        Product knownProduct = new Product();
        knownProduct.setCategoryId(31L);
        Product unknownProduct = new Product();
        unknownProduct.setCategoryId(32L);
        ProductVO knownProductVO = new ProductVO();
        ProductVO unknownProductVO = new ProductVO();
        Category category = new Category();
        category.setId(31L);
        category.setCategoryName("热菜");

        when(merchantConverter.toMerchantDetailVO(merchant)).thenReturn(detail);
        when(categoryService.getCategoriesByIds(Set.of(31L, 32L))).thenReturn(List.of(category));
        when(merchantConverter.toProductVO(knownProduct)).thenReturn(knownProductVO);
        when(merchantConverter.toProductVO(unknownProduct)).thenReturn(unknownProductVO);

        MerchantDetailVO result = merchantQueryService.toMerchantDetailVO(merchant,
                Map.of(31L, List.of(knownProduct), 32L, List.of(unknownProduct)));

        assertEquals(2, result.getCategories().size());
        var knownCategory = result.getCategories().stream()
                .filter(value -> value.getCategoryId().equals(31L)).findFirst().orElseThrow();
        var unknownCategory = result.getCategories().stream()
                .filter(value -> value.getCategoryId().equals(32L)).findFirst().orElseThrow();
        assertEquals("热菜", knownCategory.getCategoryName());
        assertEquals(List.of(knownProductVO), knownCategory.getProducts());
        assertEquals("未知分类", unknownCategory.getCategoryName());
        assertEquals(List.of(unknownProductVO), unknownCategory.getProducts());
        verify(categoryService).getCategoriesByIds(Set.of(31L, 32L));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWrapper<?, ?, ?> capturedMerchantWrapper() {
        ArgumentCaptor<Wrapper<Merchant>> wrapperCaptor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(merchantMapper).selectList(wrapperCaptor.capture());
        return (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
    }
}
