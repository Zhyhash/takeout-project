package org.example.takeout.Category.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.takeout.Category.Entity.Category;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
