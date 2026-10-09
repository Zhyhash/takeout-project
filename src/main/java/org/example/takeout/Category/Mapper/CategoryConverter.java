package org.example.takeout.Category.Mapper;


import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Category.VO.CategoryVO;
import org.example.takeout.Category.VO.CreateCategoryVO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring", imports = CategoryStatusEnum.class)
public interface CategoryConverter {
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    CategoryVO toCategoryVO(Category category);

    List<CategoryVO> toCategoryVOList(List<Category> categories);

    @Mapping(target = "statusDesc", expression = "java(CategoryStatusEnum.ACTIVE.getDesc())")
    CreateCategoryVO toCreateCategoryVO(Category category);

}
