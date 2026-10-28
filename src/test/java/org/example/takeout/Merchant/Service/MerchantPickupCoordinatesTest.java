package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Category.Service.CategoryCommandService;
import org.example.takeout.Common.Auth.LoginAttemptLimiter;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Common.Utils.MyScurity.JWTUtils;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantPickupCoordinatesTest {

    private final MerchantMapper mapper = mock(MerchantMapper.class);
    private final MerchantAccountService service = new MerchantAccountService(mapper,
            Mappers.getMapper(MerchantConverter.class), mock(JWTUtils.class),
            mock(LoginAttemptLimiter.class), mock(CategoryCommandService.class));

    @BeforeAll
    static void initializeMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Merchant.class);
    }

    @AfterEach
    void clearContext() {
        MerchantContextHolder.clear();
    }

    @Test
    void configuringPickupCoordinatesPersistsAndReturnsThePairWithoutErasingOtherShopFields() {
        MerchantContextHolder.setMerchantId(201L);
        Merchant merchant = new Merchant();
        merchant.setId(201L);
        merchant.setStatus(MerchantStatusEnum.BUSINESS_CLOSED.getCode());
        merchant.setMerchantName("Pickup Shop");
        merchant.setAddress("Merchant Road");
        when(mapper.selectOne(any())).thenReturn(merchant);
        when(mapper.updateById(any(Merchant.class))).thenReturn(1);
        MerchantUpdateDTO dto = new MerchantUpdateDTO();
        dto.setLongitude(new BigDecimal("121.4737"));
        dto.setLatitude(new BigDecimal("31.2304"));

        var result = service.updateMerchant(dto);

        assertThat(result.getLongitude()).isEqualByComparingTo(dto.getLongitude());
        assertThat(result.getLatitude()).isEqualByComparingTo(dto.getLatitude());
        assertThat(result.getMerchantName()).isEqualTo("Pickup Shop");
        assertThat(result.getAddress()).isEqualTo("Merchant Road");
        verify(mapper).updateById(argThat((Merchant saved) -> saved.getId() == 201L
                && saved.getLongitude().compareTo(dto.getLongitude()) == 0
                && saved.getLatitude().compareTo(dto.getLatitude()) == 0));
    }

    @Test
    void incompleteOrUnsupportedCoordinatesAreRejectedBeforeDatabaseAccess() {
        MerchantUpdateDTO incomplete = new MerchantUpdateDTO();
        incomplete.setLongitude(BigDecimal.ONE);
        MerchantUpdateDTO unsupported = new MerchantUpdateDTO();
        unsupported.setLongitude(BigDecimal.ONE);
        unsupported.setLatitude(new BigDecimal("86"));

        for (MerchantUpdateDTO dto : new MerchantUpdateDTO[]{incomplete, unsupported}) {
            BusinessException failure = assertThrows(BusinessException.class, () -> service.updateMerchant(dto));
            assertThat(failure.getCodeEnum()).isEqualTo(ResultCodeEnum.PARAM_ERROR);
        }
        verifyNoInteractions(mapper);
    }
}
