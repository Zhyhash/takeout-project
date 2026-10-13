package org.example.takeout.Cart.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.CartHeader.Entity.CartHeader;
import org.example.takeout.CartHeader.Manager.CartHeaderManager;
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
    private final CartHeaderManager cartHeaderManager;

    public CartAvailableResult prepareCheckout(Long userId) {
        List<CartItem> allItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId));
        if (allItems.isEmpty()) return new CartAvailableResult();

        // 批量查询商品
        List<Long> productIds = allItems.stream().map(CartItem::getProductId).toList();


        Map<Long, Product> productMap = productQueryService.selectProductsByIds(productIds);
        CartHeader cartHeader = cartHeaderManager.getByUserId(userId);
        if (cartHeader == null) {
            return new CartAvailableResult();
        }
        Long requestedMerchantId = cartHeader.getMerchantId();
        Merchant merchant = requestedMerchantId == null
                ? null
                : merchantQueryService.selectMerchantById(requestedMerchantId);

        List<CartItem> available = new ArrayList<>();
        for (CartItem item : allItems) {
            Product product = productMap.get(item.getProductId());
            if (isAvailable(product, merchant)) {
                available.add(item);
            }
        }
        CartAvailableResult cartAvailableResult = new CartAvailableResult();
        cartAvailableResult.setAllItems(allItems);
        cartAvailableResult.setAvailableItems(available);
        cartAvailableResult.setProductMap(productMap);
        if (merchant != null) {
            cartAvailableResult.setMerchantId(merchant.getId());
            cartAvailableResult.setMerchantName(merchant.getMerchantName());
        }
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
