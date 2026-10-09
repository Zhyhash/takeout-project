package org.example.takeout.Cart.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.CartHeader.Manager.CartHeaderManager;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CartCommandService {
    private final CartMapper cartMapper;
    private final CartHeaderManager cartHeaderManager;

    public void consumeCheckedOutItems(Long userId, List<CartItem> items) {
        cartHeaderManager.lock(userId);

        for (CartItem item : items) {
            int affected = cartMapper.consumeQuantity(
                    userId,
                    item.getId(),
                    item.getQuantity());

            if (affected != 1) {
                throw new BusinessException(
                        ResultCodeEnum.BUSINESS_ERROR,
                        "购物车商品数量已发生变化，请重新确认"
                );
            }

            cartMapper.deleteIfEmpty(userId, item.getId());
        }

        boolean exists = cartMapper.existsByUserId(userId);
        if (!exists) {
            cartHeaderManager.unbindMerchant(userId);
        }
    }
}
