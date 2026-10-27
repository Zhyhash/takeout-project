package org.example.takeout.Merchant.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.springframework.stereotype.Service;

/**
 * 面向跨模块调用的商家基础只读查询服务。
 * 负责按 ID 查询商家及检查营业状态，避免调用方引入商家业务编排依赖。
 * 历史上曾将查询合并到 MerchantService，形成以下循环依赖，需保留独立查询边界：
 * CartCheckoutService -> MerchantService -> DeliveryTaskService -> OrderService -> CartCheckoutService。
 */
@Service
@RequiredArgsConstructor
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
