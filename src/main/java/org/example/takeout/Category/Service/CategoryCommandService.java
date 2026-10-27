package org.example.takeout.Category.Service;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.springframework.stereotype.Service;

/**
 * 分类初始化的写入入口。
 * 面向商家注册流程，为新商家创建启用状态的默认商品分类。
 */
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
