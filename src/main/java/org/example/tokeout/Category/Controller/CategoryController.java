package org.example.tokeout.Category.Controller;

import org.example.tokeout.Category.Service.CategoryService;
import org.example.tokeout.Category.VO.CategoryVO;
import org.example.tokeout.Category.VO.CreateCategoryVO;
import org.example.tokeout.Common.Result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分类管理控制器
 * 提供商家分类的增删查接口
 */
@RestController
@RequestMapping("/category")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 获取当前商家的所有可用分类列表
     * 用于创建商品时的下拉框选择
     */
    @GetMapping
    public Result<?> list() {
        List<CategoryVO> categoryVOList = categoryService.listByMerchant();
        return Result.success(categoryVOList);
    }

    /**
     * 根据分类ID获取单个分类详情
     */
    @GetMapping("{id}")
    public Result<?> getById(@PathVariable Long id) {
        CategoryVO categoryVO = categoryService.getByIdAndMerchant(id);
        return Result.success(categoryVO);
    }

    /**
     * 创建新分类
     * @param categoryName 分类名称
     */
    @PostMapping
    public Result<?> create(@RequestParam String categoryName) {
        CreateCategoryVO createCategoryVO = categoryService.createCategory(categoryName);
        return Result.success(createCategoryVO);
    }

    /**
     * 删除指定分类
     * 删除后，该分类下的商品会自动转移到默认分类
     */
    @DeleteMapping("{id}")
    public Result<?> delete(@PathVariable Long id) {
        categoryService.deleteById(id);
        return Result.success("删除成功");
    }
}