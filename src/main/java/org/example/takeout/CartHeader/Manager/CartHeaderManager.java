package org.example.takeout.CartHeader.Manager;

import lombok.RequiredArgsConstructor;
import org.example.takeout.CartHeader.Entity.CartHeader;
import org.example.takeout.CartHeader.Mapper.CartHeaderMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class CartHeaderManager {

    private final CartHeaderMapper cartHeaderMapper;

    public CartHeader lock(Long userId) {
        return cartHeaderMapper.lockCartHeader(userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertCartHeader(Long userId) {
        CartHeader cartHeader = new CartHeader();
        cartHeader.setUserId(userId);
        int rows = cartHeaderMapper.insert(cartHeader);
        if (rows != 1) {
            throw new IllegalStateException("创建购物车总表失败，userId=" + userId + ", affectedRows=" + rows);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bindMerchant(Long userId, Long merchantId) {
        int rows = cartHeaderMapper.bindMerchant(userId, merchantId);
        if (rows != 1) {
            throw new IllegalStateException("绑定购物车商家失败，userId=" + userId + ", merchantId=" + merchantId
                    + ", affectedRows=" + rows);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unbindMerchant(Long userId) {
        int rows = cartHeaderMapper.unbindMerchant(userId);
        if (rows == 1) {
            return;
        }

        // 重复调用 clear() 这种情况，字段本来就是 NULL，数据库可能返回 0
        CartHeader cartHeader = cartHeaderMapper.selectById(userId);
        if (rows != 0 || cartHeader == null || cartHeader.getMerchantId() != null) {
            throw new IllegalStateException("解绑购物车商家失败，userId=" + userId + ", affectedRows=" + rows);
        }
    }
}
