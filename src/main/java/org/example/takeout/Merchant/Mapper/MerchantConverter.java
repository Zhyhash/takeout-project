package org.example.takeout.Merchant.Mapper;

import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;

import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface MerchantConverter {
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Merchant toMerchant(MerchantUpdateDTO dto, @MappingTarget Merchant entity);

    @AfterMapping
    default void AfterMapper(Merchant merchant, @MappingTarget MerchantUpdateVO vo) {
        Integer actualStatus = MerchantStatusEnum.calculateActualStatus(
                merchant.getStatus(),
                merchant.getOpeningTime(),
                merchant.getClosingTime()
        );
        vo.setStatus(actualStatus);
    }
}
