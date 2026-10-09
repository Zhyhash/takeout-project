package org.example.takeout.Category.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CategoryCommandService {
    private final CategoryMapper categoryMapper;

    public void createDefaultCategory(Long merchantId) {
        Category defaultCategory = new Category();
        defaultCategory.setMerchantId(merchantId);
        defaultCategory.setCategoryName("默认分类");
        defaultCategory.setIsDefault(CategoryDefaultEnum.DEFAULT.getCode());
        defaultCategory.setStatus(CategoryStatusEnum.ACTIVE.getCode());

        categoryMapper.insert(defaultCategory);
    }
}
