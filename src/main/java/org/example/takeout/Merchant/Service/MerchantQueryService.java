package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MerchantQueryService {
    private final MerchantMapper merchantMapper;
    public Map<Long, Merchant> selectMerchantsByIds(List<Long> merchantIds) {
        return merchantMapper.selectList(
                        Wrappers.<Merchant>lambdaQuery()
                                .in(Merchant::getId, merchantIds))
                .stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));
    }

    public boolean checkMerchantOpen(Long merchantId) {
        Merchant merchant = merchantMapper.selectById(merchantId);
        return merchant == null ||
                Objects.equals(merchant.getStatus(),
                        MerchantStatusEnum.BUSINESS_CLOSED.getCode());
    }
}
