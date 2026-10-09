package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Product.class);
    }

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private ProductQueryService productQueryService;

    @Test
    void visibleProductsIncludesOnSaleAndSoldOutProductsForMerchant() {
        Product onSale = product(301L, ProductStatusEnum.ON_SALE.getCode());
        Product soldOut = product(302L, ProductStatusEnum.SALE_OUT.getCode());
        List<Product> visibleProducts = List.of(onSale, soldOut);
        when(productMapper.selectList(any())).thenReturn(visibleProducts);

        List<Product> result = productQueryService.getVisibleProductsByMerchantId(201L);

        assertSame(visibleProducts, result);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Product>> wrapperCaptor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(productMapper).selectList(wrapperCaptor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains(" IN "));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(201L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(ProductStatusEnum.ON_SALE.getCode()));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(ProductStatusEnum.SALE_OUT.getCode()));
    }

    private static Product product(long id, Integer status) {
        Product product = new Product();
        product.setId(id);
        product.setMerchantId(201L);
        product.setStatus(status);
        return product;
    }
}
