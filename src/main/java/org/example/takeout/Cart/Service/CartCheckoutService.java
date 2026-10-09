package org.example.takeout.Cart.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Service.MerchantQueryService;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Service.ProductQueryService;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
public class CartCheckoutService {
    /**
     * 获取当前用户购物车中【可下单】的商品列表
     * 规则：商品状态上架 && 商家营业（未打烊）
     * 注意：不删除任何购物车记录，只是过滤
     */
    private final MerchantQueryService merchantQueryService;
    private final CartMapper cartMapper;
    private final ProductQueryService productQueryService;
    public CartAvailableResult prepareCheckout(Long userId) {
        List<CartItem> allItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId));
        if (allItems.isEmpty()) return new CartAvailableResult();

        // 批量查询商品和商家（性能优化）
        List<Long> productIds = allItems.stream().map(CartItem::getProductId).toList();
        List<Long> merchantIds = allItems.stream().map(CartItem::getMerchantId).collect(Collectors.toList());

        Map<Long, Product> productMap = productQueryService.selectProductsByIds(productIds);

        Map<Long, Merchant> merchantMap = merchantQueryService.selectMerchantsByIds(merchantIds);

        List<CartItem> available = new ArrayList<>();
        for (CartItem item : allItems) {
            Product product = productMap.get(item.getProductId());
            Merchant merchant = merchantMap.get(item.getMerchantId());
            if (isAvailable(product, merchant)) {
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

    private boolean isAvailable(Product product, Merchant merchant) {
        return product != null
                && Objects.equals(
                        product.getStatus(),
                        ProductStatusEnum.ON_SALE.getCode())
                && Objects.equals(
                        product.getIsDeleted(),
                        DeleteConstant.NOT_DELETED)
                && merchant != null
                && !Objects.equals(
                        merchant.getStatus(),
                        MerchantStatusEnum.BUSINESS_CLOSED.getCode());
    }
}
