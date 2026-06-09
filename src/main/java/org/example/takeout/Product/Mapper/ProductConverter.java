package org.example.takeout.Product.Mapper;

import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.Product.VO.ProductVO;
import org.mapstruct.*;

@Mapper(componentModel = "spring", imports = {ProductStatusEnum.class, MerchantContextHolder.class}) // 把需要调用的静态类 import 进来
public interface ProductConverter {
    @Mapping(source = "product.id", target = "id") // 如果字段名完全一样，这行甚至可以省略
    @Mapping(source = "category.categoryName", target = "categoryName") // 直接把分类名映射过去
    MerchantProductVO toMerchantProductVO(Product product, Category category);

    @Mapping(target = "status", expression = "java(ProductStatusEnum.OFF_SALE.getCode())") //  执行 java 表达式赋值枚举
    @Mapping(target = "merchantId", expression = "java(MerchantContextHolder.getMerchantId())") //  执行 java 表达式从 ThreadLocal 拿 ID
    @Mapping(target = "isDeleted", constant = "0") // 直接定死常量
    Product toProduct(CreateProductDTO createProductDTO);

}
