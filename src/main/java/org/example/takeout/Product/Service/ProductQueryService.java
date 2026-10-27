package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品模块的只读查询入口。
 * 为购物车、结算和店铺展示提供商品批量查询、在售商品查询及可见商品查询。
 */
@Service
@RequiredArgsConstructor
public class ProductQueryService {
    private final ProductMapper productMapper;

    //对cart模块 调用接口
    public Map<Long, Product> selectProductsByIds(Collection<Long> productIds) {
        return productMapper.selectList(Wrappers.<Product>lambdaQuery().
                        in(Product::getId, productIds))
                .stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
    }

    public Product findOnSaleProduct(Long productId) {
        return productMapper.selectOne(Wrappers.<Product>lambdaQuery().
                eq(Product::getId, productId).
                eq(Product::getStatus, ProductStatusEnum.ON_SALE.getCode()));
    }

    //对merchant模块 调用接口
    public List<Product> getVisibleProductsByMerchantId(Long merchantId) {
        return productMapper.selectList(
                Wrappers.<Product>lambdaQuery()
                        .eq(Product::getMerchantId, merchantId)
                        .in(Product::getStatus,
                                ProductStatusEnum.ON_SALE.getCode(),
                                ProductStatusEnum.SALE_OUT.getCode())
        );
    }
}
