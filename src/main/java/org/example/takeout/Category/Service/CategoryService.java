package org.example.takeout.Category.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryConverter;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Category.VO.CategoryVO;
import org.example.takeout.Category.VO.CreateCategoryVO;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Service.ProductCommandService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {
    private final CategoryMapper categoryMapper;
    private final CategoryConverter categoryConverter;
    private final ProductCommandService productCommandService;

    // NOTE:商户查自己所有可用分类（用于创建商品时的下拉框）
    public List<CategoryVO> listByMerchant(){
        List<Category> categories = categoryMapper.selectList(Wrappers.<Category>lambdaQuery().
                eq(Category::getMerchantId, MerchantContextHolder.getMerchantId()).
                eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode()));
        return categoryConverter.toCategoryVOList(categories);
    };

    // NOTE：按 code + merchantId 查单个分类，不存在抛 BusinessException
    public CategoryVO getByIdAndMerchant(Long categoryId){
        Category category = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery().
                eq(Category::getMerchantId, MerchantContextHolder.getMerchantId()).
                eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode()).
                eq(Category::getId, categoryId));
        if (category == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"不存在这个分类或者分类失效");
        }

        return categoryConverter.toCategoryVO(category);
    };

    //NOTE：用户删除分类
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long categoryId){
        Long merchantId = MerchantContextHolder.getMerchantId();
        LambdaQueryWrapper<Category> categoryWrapper = new LambdaQueryWrapper<>();
        categoryWrapper.eq(Category::getId, categoryId)
                .eq(Category::getMerchantId, merchantId)
                .last("FOR UPDATE");
        Category category = categoryMapper.selectOne(categoryWrapper);
        if (category == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"分类种类不存在或不属于商家");
        }
        if (Objects.equals(category.getIsDefault(), CategoryDefaultEnum.DEFAULT.getCode())
                || category.getIsDefault() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"默认分类不能删除");
        }
        Category defaultCategory =
                categoryMapper.selectOne(
                        Wrappers.<Category>lambdaQuery()
                                .eq(Category::getMerchantId, merchantId)
                                .eq(Category::getIsDefault, CategoryDefaultEnum.DEFAULT.getCode())
                );
        if(defaultCategory == null){
            throw new BusinessException(ResultCodeEnum.DATABASE_ERROR,"默认分类不存在");
        }
        productCommandService.migrateProductsToCategory(merchantId,categoryId,defaultCategory.getId());
        categoryMapper.deleteById(categoryId);
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateCategoryVO createCategory(String categoryName){
        Category oldCategory = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery().
                eq(Category::getMerchantId, MerchantContextHolder.getMerchantId()).
                eq(Category::getCategoryName, categoryName).
                eq(Category::getStatus,CategoryStatusEnum.ACTIVE.getCode()));
        if (oldCategory != null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"已经有重复分类，无法创建");
        }


        Category category = new Category();

        category.setMerchantId(MerchantContextHolder.getMerchantId());
        category.setIsDefault(CategoryDefaultEnum.CLASSIFICATION.getCode());
        category.setCategoryName(categoryName);
        category.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        //数据库的category_name的唯一的,重复会直接回滚
        categoryMapper.insert(category);

        return categoryConverter.toCreateCategoryVO(category);
    }

    //对外product的暂时接口
    public Category lockActiveCategory(Long categoryId, Long merchantId) {
        return categoryMapper.selectOne(
                Wrappers.<Category>lambdaQuery()
                        .eq(Category::getId, categoryId)
                        .eq(Category::getMerchantId, merchantId)
                        .eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode())
                        .last("FOR UPDATE")) ;
    }
    public Category getCategory(Long categoryId){
        Category category = categoryMapper.selectById(categoryId);

        if (category == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"分类不存在");
        }

        return category;
    }
    public Map<Long, Category> getCategoryMap(List<Long> categoryIds,Long merchantId) {
        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .eq(Category::getMerchantId, merchantId)
                        .in(Category::getId, categoryIds))
                .stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (first, second) -> first));
    }

    //对merchant的暂时接口
    public List<Category> getCategoriesByIds(Collection<Long> categoryIds) {
        return categoryMapper.selectList(
                Wrappers.<Category>lambdaQuery()
                        .in(Category::getId, categoryIds)
        );
    }
}
