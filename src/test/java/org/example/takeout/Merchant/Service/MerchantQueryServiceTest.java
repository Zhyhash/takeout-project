package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.MerchantListVO;
import org.example.takeout.Merchant.VO.MerchantDetailVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantQueryServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Merchant.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Product.class);
    }

    @Mock
    private MerchantMapper merchantMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private MerchantConverter merchantConverter;

    @InjectMocks
    private MerchantQueryService merchantQueryService;

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
    void customerShopDetailIncludesOnSaleAndSaleOutProducts() {
        Merchant merchant = new Merchant();
        merchant.setId(201L);
        merchant.setStatus(MerchantStatusEnum.BUSINESS_OPEN.getCode());
        MerchantDetailVO detail = new MerchantDetailVO();

        when(merchantMapper.selectOne(any())).thenReturn(merchant);
        when(productMapper.selectList(any())).thenReturn(List.of());
        when(merchantConverter.toMerchantDetailVO(merchant)).thenReturn(detail);

        assertSame(detail, merchantQueryService.getMerchantDetailWithGroupedProducts(201L));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Product>> wrapperCaptor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(productMapper).selectList(wrapperCaptor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains(" IN "));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(ProductStatusEnum.ON_SALE.getCode()));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(ProductStatusEnum.SALE_OUT.getCode()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWrapper<?, ?, ?> capturedMerchantWrapper() {
        ArgumentCaptor<Wrapper<Merchant>> wrapperCaptor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(merchantMapper).selectList(wrapperCaptor.capture());
        return (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
    }
}
