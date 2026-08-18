package org.example.takeout.Cart.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
@Service
public class cartDomainService {
    /**
     * 获取当前用户购物车中【可下单】的商品列表
     * 规则：商品状态上架 && 商家营业（未打烊）
     * 注意：不删除任何购物车记录，只是过滤
     */
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;
    public CartAvailableResult getAvailableCartItems(Long userId) {
        List<CartItem> allItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId));
        if (allItems.isEmpty()) return new CartAvailableResult();

        // 批量查询商品和商家（性能优化）
        List<Long> productIds = allItems.stream().map(CartItem::getProductId).toList();
        List<Long> merchantIds = allItems.stream().map(CartItem::getMerchantId).collect(Collectors.toList());

        Map<Long, Product> productMap = productMapper.selectList(Wrappers.<Product>lambdaQuery().in(Product::getId, productIds)
                .eq(Product::getStatus, ProductStatusEnum.ON_SALE.getCode())
                .eq(Product::getIsDeleted, DeleteConstant.NOT_DELETED))
                .stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        Map<Long, Merchant> merchantMap = merchantMapper.selectList(
                        Wrappers.<Merchant>lambdaQuery()
                                .in(Merchant::getId, merchantIds)
                                .ne(Merchant::getStatus, MerchantStatusEnum.BUSINESS_CLOSED.getCode()))
                .stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));

        List<CartItem> available = new ArrayList<>();
        for (CartItem item : allItems) {
            Product product = productMap.get(item.getProductId());
            Merchant merchant = merchantMap.get(item.getMerchantId());
            if (product != null && ProductStatusEnum.ON_SALE.getCode().equals(product.getStatus())
                    && merchant != null && !MerchantStatusEnum.BUSINESS_CLOSED.getCode().equals(merchant.getStatus())) {
                available.add(item);
            }
        }
        CartAvailableResult cartAvailableResult = new CartAvailableResult();
        cartAvailableResult.setAllItems(allItems);
        cartAvailableResult.setAvailableItems(available);
        cartAvailableResult.setProductMap(productMap);
        cartAvailableResult.setMerchantMap(merchantMap);
        return cartAvailableResult;
    }
}
