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
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.ProductVO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface MerchantConverter {
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Merchant toMerchant(MerchantUpdateDTO dto, @MappingTarget Merchant entity);

    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "merchantStatusDescription")
    MerchantUpdateVO toMerchantUpdateVO(Merchant entity);

    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "merchantStatusDescription")
    MerchantListVO toMerchantListVO(Merchant entity);

    // 在 MerchantStructMapper 接口中直接加上这个：
    PageInfo<MerchantListVO> toPageInfoVO(PageInfo<Merchant> pageInfo);

    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "productStatusDescription")
    @Mapping(source = "stock", target = "inStock", qualifiedByName = "hasStock")
    ProductVO toProductVO(Product product);

    @Mapping(source = "status", target = "statusDesc", qualifiedByName = "merchantStatusDescription")
    MerchantDetailVO toMerchantDetailVO(Merchant merchant);

    Merchant toMerchant(MerchantRegisterDTO dto);

    @Named("merchantStatusDescription")
    default String merchantStatusDescription(Integer status) {
        return MerchantStatusEnum.descriptionOf(status);
    }

    @Named("productStatusDescription")
    default String productStatusDescription(Integer status) {
        return ProductStatusEnum.descriptionOf(status);
    }

    @Named("hasStock")
    default boolean hasStock(Integer stock) {
        return stock != null && stock > 0;
    }
}
