package org.example.tokeout.Category.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.tokeout.Category.Entity.Category;
import org.example.tokeout.Category.Mapper.CategoryMapper;
import org.example.tokeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.tokeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.tokeout.Category.VO.CategoryVO;
import org.example.tokeout.Category.VO.CreateCategoryVO;
import org.example.tokeout.Common.Exception.BusinessException;
import org.example.tokeout.Common.Utils.Context.UserContextHolder;
import org.example.tokeout.Product.Entity.Product;
import org.example.tokeout.Product.Mapper.ProductMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class CategoryService {
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;

    // NOTE:商户查自己所有可用分类（用于创建商品时的下拉框）
    //抽取方法，专注业务逻辑
    private List<CategoryVO> toCategoryVOList(List<Category> categoryList) {
        return categoryList.stream()
                .map(category -> {
                    CategoryVO vo = new CategoryVO();
                    BeanUtils.copyProperties(category, vo);
                    return vo;
                })
                .collect(Collectors.toList());
    }
    public List<CategoryVO> listByMerchant(){
        List<Category> categories = categoryMapper.selectList(Wrappers.<Category>lambdaQuery().
                eq(Category::getMerchantId, UserContextHolder.getUserId()).
                eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode()));
        return toCategoryVOList(categories);
    };

    // NOTE：按 code + merchantId 查单个分类，不存在抛 BusinessException
    public CategoryVO getByIdAndMerchant(Long categoryId){
        Category category = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery().
                eq(Category::getMerchantId, UserContextHolder.getUserId()).
                eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode()).
                eq(Category::getId, categoryId));
        if (category == null)
            throw new BusinessException("不存在这个分类或者分类失效");

        CategoryVO categoryVO = new CategoryVO();
        BeanUtils.copyProperties(category, categoryVO);
        return categoryVO;
    };

    //NOTE：用户删除分类
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long categoryId){
        //拿到种类
        Category category = categoryMapper.selectById(categoryId);
        if (category == null)
            throw new BusinessException("分类种类不存在或不属于商家");
        if (Objects.equals(category.getIsDefault(), CategoryDefaultEnum.DEFAULT.getCode())) {
            throw new BusinessException("默认分类不能删除");
        }
        Category defaultCategory =
                categoryMapper.selectOne(
                        Wrappers.<Category>lambdaQuery()
                                .eq(Category::getMerchantId, UserContextHolder.getUserId())
                                .eq(Category::getIsDefault, CategoryDefaultEnum.DEFAULT.getCode())
                );
        productMapper.update(
                null,
                Wrappers.<Product>lambdaUpdate()
                        .set(Product::getCategoryId, defaultCategory.getId())
                        .eq(Product::getCategoryId, categoryId)
        );
        categoryMapper.deleteById(categoryId);
    }
    @Transactional(rollbackFor = Exception.class)
    public CreateCategoryVO createCategory(String categoryName){
        CreateCategoryVO createCategoryVO = new CreateCategoryVO();
        Category category = new Category();

        category.setMerchantId(UserContextHolder.getUserId());
        category.setIsDefault(CategoryDefaultEnum.CLASSIFICATION.getCode());
        category.setCategoryName(categoryName);
        category.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        //数据库的category_name的唯一的,重复会直接回滚
        categoryMapper.insert(category);

        createCategoryVO.setId(category.getId());
        createCategoryVO.setCategoryName(categoryName);
        createCategoryVO.setStatusDesc(CategoryStatusEnum.ACTIVE.getDesc());

        return  createCategoryVO;
    }
}
