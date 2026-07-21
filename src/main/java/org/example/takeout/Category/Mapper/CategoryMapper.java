package org.example.takeout.Category.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Category.Entity.Category;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
