package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductService {
    public static final String DEFAULT_PRODUCT_IMAGE_URL = "/images/default-product.svg";

    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductConverter productConverter;

    //NOTE:抽取方法，转换VO
    public MerchantProductVO toMerchantProductVO(Product product, Category category) {
        // 从 product 实体中拷贝基础属性（此时 product 已经被回填了 id）
        return productConverter.toMerchantProductVO(product,category);
    }

    //NOTE:抽取方法，转换Product
    public Product toProduct(CreateProductDTO createProductDTO){
        Product product = productConverter.toProduct(createProductDTO, MerchantContextHolder.getMerchantId());
        product.setImageUrl(resolveImageUrl(product.getImageUrl()));
        return product;
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return DEFAULT_PRODUCT_IMAGE_URL;
        }
        return imageUrl.trim();
    }

    //NOTE:抽取方法，扣减库存，目前只用于orderService
    public void decreaseStock(Long productId, Integer quantity){
        if (productId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品信息不能为空");
        }

        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "扣减数量必须大于0");
        }
        UpdateWrapper<Product> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", productId)
                .ge("stock", quantity)
                .setSql("stock = stock - " + quantity);
        int row= productMapper.update(null,wrapper);
        if (row != 1)
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"创建订单失败");
    }
    //NOTE:创建商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO createProduct(@NonNull CreateProductDTO createProductDTO) {
        LambdaQueryWrapper<Category> categoryWrapper = new LambdaQueryWrapper<>();
        categoryWrapper.eq(Category::getId, createProductDTO.getCategoryId())
                .eq(Category::getMerchantId, MerchantContextHolder.getMerchantId())
                .eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode())
                .last("FOR UPDATE");
        Category category = categoryMapper.selectOne(categoryWrapper);
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
        Product product = getProduct(productId);
        validateShelfChangeLegal(product, ProductStatusEnum.ON_SALE);
        Category category = getCategory(product.getCategoryId());

        if (!ProductStatusEnum.ON_SALE.getCode().equals(product.getStatus())) {
            changeProductStatus(product, ProductStatusEnum.ON_SALE);
        }

        return toMerchantProductVO(product,category);
    }

    //NOTE:下架商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO offShelf(Long productId){
        Product product = getProduct(productId);
        validateShelfChangeLegal(product, ProductStatusEnum.OFF_SALE);
        Category category = getCategory(product.getCategoryId());

        if (!ProductStatusEnum.OFF_SALE.getCode().equals(product.getStatus())) {
            changeProductStatus(product, ProductStatusEnum.OFF_SALE);
        }

        return toMerchantProductVO(product,category);
    }

    private void validateShelfChangeLegal(Product product, ProductStatusEnum targetStatus) {
        if (product == null){
            String message = targetStatus == ProductStatusEnum.ON_SALE ? "不存在该商品" : "商品不存在";
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,message);
        }

        Integer currentStatus = product.getStatus();
        if (targetStatus.getCode().equals(currentStatus)) {
            return;
        }

        if (targetStatus == ProductStatusEnum.ON_SALE) {
            if (!ProductStatusEnum.OFF_SALE.getCode().equals(currentStatus)) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"当前状态不允许上架");
            }
            if (product.getStock() == null || product.getStock() <= 0) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存不足，无法上架");
            }
            if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"价格无效，无法上架");
            }
            if (product.getProductName() == null || product.getProductName().isBlank()) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品名称为空，无法上架");
            }
            return;
        }

        if (!ProductStatusEnum.ON_SALE.getCode().equals(currentStatus)
                && !ProductStatusEnum.SALE_OUT.getCode().equals(currentStatus)) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"当前状态不允许下架");
        }
    }

    private void changeProductStatus(Product product, ProductStatusEnum targetStatus) {
        LambdaUpdateWrapper<Product> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Product::getId, product.getId());

        Product updateEntity = new Product();
        updateEntity.setId(product.getId());
        updateEntity.setVersion(product.getVersion());
        updateEntity.setStatus(targetStatus.getCode());

        int i = productMapper.update(updateEntity,wrapper);
        if (i!=1) {
            String message = targetStatus == ProductStatusEnum.ON_SALE ? "商品上架失败" : "商品下架失败";
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,message);
        }

        product.setStatus(targetStatus.getCode());
        product.setVersion(product.getVersion()+1);
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
        PageHelper.startPage(pageNum, pageSize);


        List<Product> products = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getMerchantId, MerchantContextHolder.getMerchantId())
                .eq(Product::getIsDeleted, DeleteConstant.NOT_DELETED)
                .eq(status != null, Product::getStatus, status)
                .eq(categoryId != null, Product::getCategoryId, categoryId));
        PageInfo<Product> productPage = new PageInfo<>(products);
        Map<Long, Category> categoryMap = getCategoryMap(products);
        return productPage.convert(product -> toMerchantProductVO(product, categoryMap.get(product.getCategoryId())));
    }

    private Map<Long, Category> getCategoryMap(List<Product> products) {
        List<Long> categoryIds = products.stream()
                .map(Product::getCategoryId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        if (categoryIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .eq(Category::getMerchantId, MerchantContextHolder.getMerchantId())
                        .in(Category::getId, categoryIds))
                .stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (first, second) -> first));
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
