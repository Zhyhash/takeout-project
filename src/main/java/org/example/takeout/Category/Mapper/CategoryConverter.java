package org.example.takeout.Category.Mapper;


import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.VO.CategoryVO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface CategoryConverter {
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    CategoryVO toCategoryVO(Category category);

}
