package org.example.tokeout.Merchant.Mapper;

import org.example.tokeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.tokeout.Merchant.Entity.Merchant;
import org.example.tokeout.Merchant.Enums.MerchantStatusEnum;
import org.example.tokeout.Merchant.VO.MerchantListVO;
import org.example.tokeout.Merchant.VO.MerchantUpdateVO;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface MerchantConverter {
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Merchant toMerchant(MerchantUpdateDTO dto, @MappingTarget Merchant entity);

    @AfterMapping
    default void toMerchantUpdateVO(Merchant merchant, @MappingTarget MerchantUpdateVO vo) {
        // 在这里写纯 Java 代码，有完美的 IDE 提示！
        Integer actualStatus = MerchantStatusEnum.calculateActualStatus(
                merchant.getStatus(),
                merchant.getOpeningTime(),
                merchant.getClosingTime()
        );
        vo.setStatus(actualStatus);
    }
}
