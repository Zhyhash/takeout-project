package org.example.takeout.Merchant.Service;

import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantQueryServiceTest {
    @Mock
    private MerchantMapper merchantMapper;

    @InjectMocks
    private MerchantQueryService merchantQueryService;

    @ParameterizedTest
    @EnumSource(MerchantStatusEnum.class)
    void checkMerchantOpenReturnsTrueOnlyForOpenMerchant(MerchantStatusEnum status) {
        Merchant merchant = new Merchant();
        merchant.setStatus(status.getCode());
        when(merchantMapper.selectById(201L)).thenReturn(merchant);

        assertEquals(status == MerchantStatusEnum.BUSINESS_OPEN, merchantQueryService.checkMerchantOpen(201L));
    }

    @Test
    void checkMerchantOpenReturnsFalseForMissingMerchant() {
        when(merchantMapper.selectById(201L)).thenReturn(null);

        assertFalse(merchantQueryService.checkMerchantOpen(201L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 2})
    void checkMerchantOpenReturnsFalseForMissingOrUnknownStatus(Integer status) {
        Merchant merchant = new Merchant();
        merchant.setStatus(status);
        when(merchantMapper.selectById(201L)).thenReturn(merchant);

        assertFalse(merchantQueryService.checkMerchantOpen(201L));
    }
}
