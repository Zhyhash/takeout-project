package org.example.takeout.Merchant.Mapper;

import com.github.pagehelper.PageInfo;
import org.example.takeout.Merchant.DTO.MerchantRegisterDTO;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;

import org.example.takeout.Merchant.VO.MerchantDetailVO;
import org.example.takeout.Merchant.VO.MerchantListVO;
import org.example.takeout.Merchant.VO.MerchantUpdateVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.VO.ProductVO;
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

    // 在 MerchantStructMapper 接口中直接加上这个：
    PageInfo<MerchantListVO> toPageInfoVO(PageInfo<Merchant> pageInfo);

    ProductVO toProductVO(Product product);

    MerchantDetailVO toMerchantDetailVO(Merchant merchant);

    Merchant toMerchant(MerchantRegisterDTO dto);
}
