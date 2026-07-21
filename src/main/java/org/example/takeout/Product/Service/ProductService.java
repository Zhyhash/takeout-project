package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.validation.constraints.NotNull;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Constants.DeleteConstant;
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
        return productConverter.toProduct(createProductDTO,MerchantContextHolder.getMerchantId());
    }
    //NOTE:抽取方法，扣减库存，目前只用于orderService
    public void decreaseStock(Product product, Integer quantity){
        if (product == null || product.getId() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品信息不能为空");
        }

        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "扣减数量必须大于0");
        }
        UpdateWrapper<Product> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", product.getId()).
                ge("stock", quantity).
                setSql("stock = stock - {0}" , quantity);
        int row= productMapper.update(null,wrapper);
        if (row != 1)
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"创建订单失败");
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
        int i = productMapper.updateById(product);// 或者只 update status 字段
        if (i!=1)
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品上架失败");
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
        int i = productMapper.updateById(product);
        if (i!=1)
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品下架失败");
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
                eq(Product::getIsDeleted, DeleteConstant.NOT_DELETED).
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
        //NOTE:恢复商品可能触发唯一约束异常，需要统一异常处理，将数据库异常转换为业务提示。
        if (rows == 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在、无权限");
        }
    }
}
