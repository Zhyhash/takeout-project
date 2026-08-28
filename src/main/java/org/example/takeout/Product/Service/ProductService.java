package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.Cache.RedisKeyConstant;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.Product.VO.ProductVO;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductService {
    public static final String DEFAULT_PRODUCT_IMAGE_URL = "/images/default-product.svg";

    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductConverter productConverter;
    @Autowired
    private StringRedisTemplate  stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

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
    @Transactional(rollbackFor = Exception.class)
    public void decreaseStock(Long productId, Integer quantity){
        if (productId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品信息不能为空");
        }

        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "扣减数量必须大于0");
        }
        UpdateWrapper<Product> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", productId).
                eq("is_deleted",DeleteConstant.NOT_DELETED).
                eq("status",ProductStatusEnum.ON_SALE.getCode())
                .ge("stock", quantity)
                .setSql("status = CASE WHEN stock = " + quantity
                        + " THEN " + ProductStatusEnum.SALE_OUT.getCode()
                        + " ELSE status END, stock = stock - " + quantity
                        + ", version = version + 1");
        int row= productMapper.update(null,wrapper);
        if (row != 1)
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"创建订单失败");
        evictCacheIfInStockChanged(productId, -quantity);
    }

    @Transactional(rollbackFor = Exception.class)
    public void increaseStock(Long productId, Integer quantity) {
        if (productId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品信息不能为空");
        }
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"归还数量必须大于0");
        }
        if (productMapper.increaseStock(
                productId,
                quantity,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode()) != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"归还库存失败，商品可能处于异常状态");
        }
        evictCacheIfInStockChanged(productId, quantity);
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
        if (product.getStock() == null || product.getStock() < 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存异常，无法上架");
        }
        ProductStatusEnum targetStatus = product.getStock() > 0
                ? ProductStatusEnum.ON_SALE
                : ProductStatusEnum.SALE_OUT;

        if (!targetStatus.getCode().equals(product.getStatus())) {
            changeProductStatus(product, targetStatus);
        }
        evictProductDetailCache(productId);

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
        evictProductDetailCache(productId);

        return toMerchantProductVO(product,category);
    }

    public ProductVO getProductDetail(Long productId){
        ProductDetailCacheDTO productDetailCache = getProductDetailCache(productId);
        if (!Objects.equals(productDetailCache.getMerchantId(), MerchantContextHolder.getMerchantId())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在或不属于当前商家");
        }
        return productConverter.toProductVO(productDetailCache);
    }

    private ProductDetailCacheDTO getProductDetailCache(Long id){

        String key = buildProductDetailKey(id);

        String jsonString = null;
        try {
            jsonString = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("缓存取出失败，Redis连接和使用可能有异常");
        }
        if (StringUtils.hasText(jsonString)) {
            try {
                ProductDetailCacheDTO cachedProduct = objectMapper.readValue(jsonString,ProductDetailCacheDTO.class);
                if (cachedProduct.getInStock() != null) {
                    return cachedProduct;
                }
                log.info("商品缓存缺少 inStock 字段，重新加载 key={}", key);
                stringRedisTemplate.delete(key);
            } catch (JacksonException e) {
                log.warn("商品缓存解析失败，删除缓存 key={}", key, e);
                stringRedisTemplate.delete(key);
            }
        }

        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在");
        }

        ProductDetailCacheDTO dto = productConverter.toProductDetailCacheDTO(product);


        try {
            stringRedisTemplate.opsForValue().
                    set(key,objectMapper.writeValueAsString(dto),1,TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("商品缓存更新失败");
        }
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO updateProduct(Long productId,@NonNull UpdateProductDTO updateProductDTO) {
        Product updatedProduct = updateProductAndEvictCache(productId, updateProductDTO);
        return toMerchantProductVO(updatedProduct, getCategory(updatedProduct.getCategoryId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(@NotNull Long productId) {
        int rows = productMapper.delete(Wrappers.<Product>lambdaQuery()
                .eq(Product::getId, productId)
                .eq(Product::getMerchantId, MerchantContextHolder.getMerchantId())
                .eq(Product::getIsDeleted, DeleteConstant.NOT_DELETED));
        if (rows != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在、已删除或不属于当前商家");
        }
        evictProductDetailCache(productId);
    }

    private Product updateProductAndEvictCache(Long productId, UpdateProductDTO updateProductDTO){
        if (updateProductDTO.getCategoryId() != null) {
            LambdaQueryWrapper<Category> categoryWrapper = new LambdaQueryWrapper<>();
            categoryWrapper.eq(Category::getId, updateProductDTO.getCategoryId())
                    .eq(Category::getMerchantId, MerchantContextHolder.getMerchantId())
                    .eq(Category::getStatus, CategoryStatusEnum.ACTIVE.getCode());
            if (categoryMapper.selectOne(categoryWrapper) == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "分类不存在或不可用");
            }
        }

        Product product = productConverter.toProduct(updateProductDTO);
        product.setId(productId);
        if (product.getImageUrl() != null) {
            product.setImageUrl(resolveImageUrl(product.getImageUrl()));
        }
        if (product.getStock() != null) {
            Product currentProduct = getProduct(productId);
            if (currentProduct == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "商品不存在或不属于当前商家");
            }
            product.setStatus(resolveStatusAfterStockReset(
                    currentProduct.getStatus(), product.getStock()));
        }

        LambdaUpdateWrapper<Product> updateWrapper = Wrappers.<Product>lambdaUpdate()
                .eq(Product::getId, productId)
                .eq(Product::getMerchantId, MerchantContextHolder.getMerchantId())
                .eq(Product::getIsDeleted, DeleteConstant.NOT_DELETED);
        int row = productMapper.update(product, updateWrapper);
        if (row != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品修改失败");
        }
        evictProductDetailCache(productId);
        return getProduct(productId);
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
            if (!ProductStatusEnum.OFF_SALE.getCode().equals(currentStatus)
                    && !ProductStatusEnum.SALE_OUT.getCode().equals(currentStatus)) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"当前状态不允许上架");
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

    private String buildProductDetailKey(Long id){
        return RedisKeyConstant.PRODUCT_DETAIL + id;
    }

    private void evictProductDetailCache(Long productId) {
        try {
            stringRedisTemplate.delete(buildProductDetailKey(productId));
        } catch (Exception e) {
            log.error("商品缓存删除失败，商品id：{}", productId, e);
        }
    }

    private void evictCacheIfInStockChanged(Long productId, int stockDelta) {
        // 订单退库允许更新逻辑删除商品，库存回读也必须绕过逻辑删除过滤，
        // 否则回读为空会抛异常并回滚已经完成的库存归还。
        Product updatedProduct = productMapper.selectStockByIdIncludingDeleted(productId);
        if (updatedProduct == null || updatedProduct.getStock() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存更新后商品信息异常");
        }

        long newStock = updatedProduct.getStock();
        long oldStock = newStock - stockDelta;
        if ((oldStock > 0) == (newStock > 0)) {
            return;
        }
        evictProductDetailCache(productId);
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
            String message = targetStatus == ProductStatusEnum.OFF_SALE ? "商品下架失败" : "商品上架失败";
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,message);
        }

        product.setStatus(targetStatus.getCode());
        product.setVersion(product.getVersion()+1);
    }

    private Integer resolveStatusAfterStockReset(Integer currentStatus, Integer newStock) {
        if (ProductStatusEnum.OFF_SALE.getCode().equals(currentStatus)) {
            return ProductStatusEnum.OFF_SALE.getCode();
        }
        if (!ProductStatusEnum.ON_SALE.getCode().equals(currentStatus)
                && !ProductStatusEnum.SALE_OUT.getCode().equals(currentStatus)) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品状态异常，无法修改库存");
        }
        return newStock > 0
                ? ProductStatusEnum.ON_SALE.getCode()
                : ProductStatusEnum.SALE_OUT.getCode();
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
                .filter(Objects::nonNull)
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
        Integer rows = productMapper.restoreDeletedProduct(
                productId,
                merchantId,
                ProductStatusEnum.OFF_SALE.getCode());
        //如果完全没有影响数据库
        //NOTE:恢复商品可能触发唯一约束异常，需要统一异常处理，将数据库异常转换为业务提示。
        if (rows == 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在、无权限");
        }
        evictProductDetailCache(productId);
    }
}
