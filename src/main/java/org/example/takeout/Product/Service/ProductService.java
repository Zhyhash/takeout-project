package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.validation.constraints.NotNull;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductConverter productConverter;
    //NOTE:抽取方法，转换VO
    public MerchantProductVO toMerchantProductVO(Product product, @NonNull Category category) {
        // 从 product 实体中拷贝基础属性（此时 product 已经被回填了 id）
        return productConverter.toMerchantProductVO(product,category);
    }
    //NOTE:抽取方法，转换Product
    public Product toProduct(CreateProductDTO createProductDTO){
        return productConverter.toProduct(createProductDTO);
    }
    //NOTE:创建商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO createProduct(@NonNull CreateProductDTO createProductDTO) {
        Category category = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery().
                eq(Category::getId, createProductDTO.getCategoryId()).
                eq(Category::getMerchantId, MerchantContextHolder.getMerchantId()).
                eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode()));
        if (category == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"种类不存在");
        }
        Product product = toProduct(createProductDTO);
        productMapper.insert(product);
        return toMerchantProductVO(product,category);
    }

    //NOTE:上架商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO onShelf(Long productId){
        //查询商品
        Product product = getProduct(productId);
        // 不存在则抛 BusinessException
        if (product == null){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"不存在该商品");
        }
        Category category = getCategory(product.getCategoryId());
        // 2. 幂等处理：已经是上架状态，直接转换成 VO 返回
        //    不能抛异常
        if (product.getStatus().equals(ProductStatusEnum.ON_SALE.getCode())) {
            return toMerchantProductVO(product,category);
        }
        // 3. 只有下架状态才能上架，其他状态（售罄、删除等）抛业务异常
        if (!product.getStatus().equals(ProductStatusEnum.OFF_SALE.getCode())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"当前状态不允许上架");
        }
        // 4. 库存必须 > 0
        if (product.getStock() == null || product.getStock() <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存不足，无法上架");
        }
        //新增. 价格必须>=0且存在
        // 价格校验
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"价格无效，无法上架");
        }

        // 5. 可选防御：名称非空
        if (product.getProductName() == null || product.getProductName().isBlank()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品名称为空，无法上架");
        }
        // 6. 更新状态
        product.setStatus(ProductStatusEnum.ON_SALE.getCode());
        productMapper.updateById(product); // 或者只 update status 字段
        // 7. 返回 VO
        return toMerchantProductVO(product,category);
    }

    //NOTE:下架商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO offShelf(Long productId){
        Product product = getProduct(productId);
        if (product == null){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品不存在");
        }
        Category category = getCategory(product.getCategoryId());
        //幂等性
        if (product.getStatus().equals(ProductStatusEnum.OFF_SALE.getCode())) {
            return toMerchantProductVO(product,category);
        }
        //上架和售罄的本来就可以下架，不进行额外校检
        product.setStatus(ProductStatusEnum.OFF_SALE.getCode());
        productMapper.updateById(product);
        return toMerchantProductVO(product,category);
    }
    private Category getCategory(Long categoryId){
        Category category = categoryMapper.selectById(categoryId);

        if (category == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"分类不存在");
        }

        return category;
    }
    private Product getProduct(Long productId) {
        return productMapper.selectOne(Wrappers.<Product>lambdaQuery().
                eq(Product::getId, productId).
                eq(Product::getIsDeleted, 0).
                eq(Product::getMerchantId, MerchantContextHolder.getMerchantId()));
    }

    //NOTE：分页查询商品
    public PageInfo<MerchantProductVO> listProducts(int pageNum, int pageSize, Integer status, Long categoryId) {
        // 1. 开启分页（PageHelper 会自动拦截紧随其后的第一条 SQL 并加上 LIMIT）
        PageHelper.startPage(pageNum, pageSize);

        // 2. 调用在 XML 中配置好的动态 SQL 方法
        List<MerchantProductVO> merchantProductVOS = productMapper.listMerchantProducts(
                MerchantContextHolder.getMerchantId(),
                status,
                categoryId
        );
        return new PageInfo<>(merchantProductVOS);
    }
    //恢复删除的商品
    @Transactional(rollbackFor = Exception.class)
    public void restoreProduct(@NotNull Long productId) {
        Long merchantId = MerchantContextHolder.getMerchantId();
        Integer rows = productMapper.restoreDeletedProduct(productId, merchantId);
        //如果完全没有影响数据库
        //TODO：事实上我们没有解决重名问题：如果删除的商品里面有和已有的商品重名，数据库会报出主键唯一异常
        if (rows == 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品不存在、无权限或存在名称冲突");
        }
    }
}
