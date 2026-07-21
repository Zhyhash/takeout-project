package org.example.takeout.Product.Mapper;

import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", imports = {ProductStatusEnum.class, MerchantContextHolder.class, DeleteConstant.class}) // 把需要调用的静态类 import 进来
public interface ProductConverter {
    @Mapping(source = "product.id", target = "id") // 如果字段名完全一样，这行甚至可以省略
    @Mapping(source = "category.categoryName", target = "categoryName") // 直接把分类名映射过去
    @Mapping(source = "product.status",target = "status")
    MerchantProductVO toMerchantProductVO(Product product, Category category);

    @Mapping(target = "status", expression = "java(ProductStatusEnum.OFF_SALE.getCode())") //  执行 java 表达式赋值枚举
    @Mapping(target = "isDeleted", expression = "java(DeleteConstant.NOT_DELETED)")
    @Mapping(target = "merchantId", source = "merchantId")
    Product toProduct(CreateProductDTO createProductDTO,Long merchantId);

}
