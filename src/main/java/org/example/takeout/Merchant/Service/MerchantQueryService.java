package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Merchant.VO.CategoryVO;
import org.example.takeout.Merchant.VO.MerchantDetailVO;
import org.example.takeout.Merchant.VO.MerchantListVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.ProductVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//NOTE：只负责“查商家数据”（用户端也属于查询）
@Service
public class MerchantQueryService {
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private MerchantConverter merchantConverter;
    //NOTE:抽取方法，将Merchant->MerchantListVO
    public PageInfo<MerchantListVO> toMerchantListVO(List<Merchant> merchants){
        return merchantConverter.toPageInfoVO(new PageInfo<>(merchants));
    }
    //NOTE:用户查询店铺（限制页面/翻页限制）
    public PageInfo<MerchantListVO> listMerchants(Integer pageNum, Integer pageSize,String merchantName,Integer status){
        PageHelper.startPage(pageNum,pageSize);
        List<Merchant> merchants = merchantMapper.selectList(Wrappers.<Merchant>lambdaQuery().
                like(Merchant::getMerchantName, merchantName).
                eq(Merchant::getStatus, status));

        if (merchants==null||merchants.isEmpty())
            return new PageInfo<>(Collections.emptyList());
        return toMerchantListVO(merchants);
    }

    //NOTE:抽取转换方法，让下面的方法专精业务逻辑
    public MerchantDetailVO toMerchantDetailVO(Merchant merchant, Map<Long, List<Product>> categoryProductMap) {
        if (merchant == null) {
            return null;
        }

        MerchantDetailVO merchantDetailVO = merchantConverter.toMerchantDetailVO(merchant);

        // 1. 抽取转换逻辑，将 Map 转为 List<CategoryVO>
        List<CategoryVO> categoryVOs = convertToCategoryVOList(categoryProductMap);

        merchantDetailVO.setCategories(categoryVOs);

        return merchantDetailVO;
    }

    /**
     * 提取出来的私有辅助方法：专门处理商品分类数据的映射
     */
    private List<CategoryVO> convertToCategoryVOList(Map<Long, List<Product>> categoryProductMap) {
        if (CollectionUtils.isEmpty(categoryProductMap)) {
            return Collections.emptyList();
        }

        // 1.用 IN 查询，把 Map 里所有的分类 ID 一次性全查出来
        List<Category> categories = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .in(Category::getId, categoryProductMap.keySet()));

        // 2. 将查出来的 List 转换成 Map 结构 (ID -> Name)，方便在下面循环里快速“对号入座”
        Map<Long, String> categoryNameMap = Collections.emptyMap();
        if (!CollectionUtils.isEmpty(categories)) {
            categoryNameMap = categories.stream()
                    .collect(Collectors.toMap(Category::getId, Category::getCategoryName, (v1, v2) -> v1));
        }

        // 这一步必须声明为 final，以便在下面的 lambda 表达式中使用
        final Map<Long, String> finalCategoryNameMap = categoryNameMap;

        return categoryProductMap.entrySet().stream()
                .map(entry -> {
                    Long categoryCode = entry.getKey();
                    List<Product> products = entry.getValue();

                    CategoryVO categoryVO = new CategoryVO();
                    categoryVO.setCategoryCode(categoryCode);

                    // 3. 【核心修改】从内存 Map 中根据当前的 categoryCode 获取精准的名称
                    String currentCategoryName = finalCategoryNameMap.get(categoryCode);
                    categoryVO.setCategoryName(currentCategoryName != null ? currentCategoryName : "未知分类");

                    // 核心提取逻辑保持不变
                    List<ProductVO> productVOs = products.stream()
                            .map(product -> merchantConverter.toProductVO(product))
                            .collect(Collectors.toList());

                    categoryVO.setProducts(productVOs);
                    return categoryVO;
                })
                .collect(Collectors.toList());
    }
    //NOTE:获取某个商家的详情
    public MerchantDetailVO getMerchantDetailWithGroupedProducts(Long merchantId){
        if (merchantId == null)
            //直接抛出异常，查不到才是返回new MerchantDetailVO
            throw  new IllegalArgumentException("merchantId不能为空");
        Merchant merchant = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().
                eq(Merchant::getId, merchantId).//这里的状态可以取消，我认为封禁的商家应该不展示(为后续的额外状态准备)
                        in(Merchant::getStatus, Arrays.asList(
                        MerchantStatusEnum.BUSINESS_OPEN.getCode(),
                        MerchantStatusEnum.BUSINESS_CLOSED.getCode()
                )));

        if (merchant == null){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商家不存在或违规");
        }
        List<Product> products = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getMerchantId, merchant.getId()).
                eq(Product::getStatus, ProductStatusEnum.ON_SALE.getCode()));

        Map<Long, List<Product>> collect = products.stream().
                collect(Collectors.groupingBy(Product::getCategoryId));
        return toMerchantDetailVO(merchant,collect);
    }
}
