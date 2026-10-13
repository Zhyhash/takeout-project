package org.example.takeout.Merchant.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
/**
 * Merchant 模块的只读查询入口。
 * 面向仅需要商家数据的跨模块调用方，
 * 不引入 MerchantService 中的订单、配送等业务编排依赖。
 */
// WARNING:
// 该查询边界不能直接合并回 MerchantService。
// 曾合并过，会形成：
// CartCheckoutService （该类可以切断这里）-> MerchantService -> DeliveryTaskService
// -> OrderService -> CartCheckoutService
public class MerchantQueryService {
    private final MerchantMapper merchantMapper;

    public Merchant selectMerchantById(Long merchantId) {
        return merchantMapper.selectById(merchantId);
    }

    /**
     * 商家存在且处于营业状态时返回 true。
     */
    public boolean checkMerchantOpen(Long merchantId) {
        Merchant merchant = selectMerchantById(merchantId);
        return merchant != null
                && MerchantStatusEnum.BUSINESS_OPEN.getCode().equals(merchant.getStatus());
    }
}
