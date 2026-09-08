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
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Cache.ProductCacheService;
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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductService {

    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductConverter productConverter;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProductCacheService productCacheService;

    public static final String DEFAULT_PRODUCT_IMAGE_URL = "/images/default-product.svg";

    private static final String NULL_PRODUCT_CACHE = "__NULL__";
    private static final long PRODUCT_CACHE_LOCK_TTL_SECONDS = 10L;
    private static final int PRODUCT_CACHE_RETRY_COUNT = 5;
    private static final long PRODUCT_CACHE_RETRY_INTERVAL_MILLIS = 50L;

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
        ProductDetailCacheDTO productDetailCache;
        try {
            productDetailCache = getProductDetailCache(productId);
        } catch (RedisCacheUnavailableException e) {
            log.warn(
                    "Redis不可用，商品详情降级查询MySQL，productId={}",
                    productId,
                    e
            );
            productDetailCache =
                    loadProductDetailFromMysqlOnly(productId);
        }

        if (!Objects.equals(productDetailCache.getMerchantId(), MerchantContextHolder.getMerchantId())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在或不属于当前商家");
        }
        return productConverter.toProductVO(productDetailCache);
    }

    private ProductDetailCacheDTO getProductDetailCache(Long id){
        String cacheKey = buildProductDetailKey(id);
        ProductDetailCacheDTO cachedProduct = readProductDetailCache(cacheKey);
        if (cachedProduct != null) {
            return cachedProduct;
        }

        String lockKey = buildProductLockKey(id);
        String lockToken = UUID.randomUUID().toString();
        boolean locked;

        locked = productCacheService.tryLock(
                lockKey,
                lockToken,
                PRODUCT_CACHE_LOCK_TTL_SECONDS,
                TimeUnit.SECONDS
        );

        if (!locked) {
            return retryReadProductDetailCache(id, cacheKey);
        }

        try {
            // 获得锁后再次查询，避免其他请求已经完成缓存重建。
            cachedProduct = readProductDetailCache(cacheKey);
            if (cachedProduct != null) {
                return cachedProduct;
            }
            return loadProductDetailAndCache(id, cacheKey);
        } finally {
            // Lua 会先比对 lockToken，只释放当前请求持有的锁。
            productCacheService.unlock(lockKey, lockToken);
        }
    }


    private ProductDetailCacheDTO readProductDetailCache(String cacheKey) {
        String cachedJson = productCacheService.get(cacheKey);

        if (!StringUtils.hasText(cachedJson)) {
            return null;
        }
        if (NULL_PRODUCT_CACHE.equals(cachedJson)) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在");
        }

        try {
            // StringRedisTemplate 读取的是 JSON 字符串：在这里反序列化为 DTO。
            ProductDetailCacheDTO cachedProduct = objectMapper.readValue(
                    cachedJson,
                    ProductDetailCacheDTO.class
            );
            if (cachedProduct != null && cachedProduct.getInStock() != null) {
                return cachedProduct;
            }
            log.info("商品缓存缺少 inStock 字段，重新加载 key={}", cacheKey);
        } catch (JacksonException e) {
            log.warn("商品缓存解析失败，删除缓存 key={}", cacheKey, e);
        }

        productCacheService.delete(cacheKey);
        return null;
    }

    //NOTE:降级数据库查询并写入缓存
    private ProductDetailCacheDTO loadProductDetailAndCache(Long id, String cacheKey) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            productCacheService.set(
                    cacheKey,
                    NULL_PRODUCT_CACHE,
                    randomCacheTtlMinutes(2, 5),
                    TimeUnit.MINUTES
            );
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在");
        }

        ProductDetailCacheDTO dto = productConverter.toProductDetailCacheDTO(product);
        // StringRedisTemplate 只能写字符串：在这里把 DTO 序列化为 JSON。
        String cacheJson = objectMapper.writeValueAsString(dto);
        productCacheService.set(
                cacheKey,
                cacheJson,
                randomCacheTtlMinutes(50, 70),
                TimeUnit.MINUTES
        );
        return dto;
    }

    //NOTE：没有抢到锁的时候的等待重试
    private ProductDetailCacheDTO retryReadProductDetailCache(Long id, String cacheKey) {
        for (int attempt = 0; attempt < PRODUCT_CACHE_RETRY_COUNT; attempt++) {

            try {
                Thread.sleep(PRODUCT_CACHE_RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(
                        ResultCodeEnum.BUSINESS_ERROR,
                        "商品缓存等待被中断"
                );
            }

            ProductDetailCacheDTO cachedProduct = readProductDetailCache(cacheKey);
            if (cachedProduct != null) {
                return cachedProduct;
            }

        }
        // Redis 不可用或锁持有时间过长时保证业务可用，允许降级查询数据库。
        log.warn("等待商品缓存重建超时，降级查询数据库，productId={}", id);
        return loadProductDetailAndCache(id, cacheKey);
    }


    private ProductDetailCacheDTO loadProductDetailFromMysqlOnly(Long productId) {
        Product product = productMapper.selectById(productId);

        if (product == null) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "查询的商品不存在"
            );
        }

        return productConverter.toProductDetailCacheDTO(product);
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

    private String buildProductLockKey(Long id){
        return "Lock:"+RedisKeyConstant.PRODUCT_DETAIL + id;
    }

    private long randomCacheTtlMinutes(long minMinutes, long maxMinutes) {
        if (minMinutes <= 0 || maxMinutes < minMinutes) {
            throw new IllegalArgumentException("缓存TTL范围不正确");
        }

        return ThreadLocalRandom.current()
                .nextLong(minMinutes, maxMinutes + 1);
    }

    private void evictProductDetailCache(Long productId) {
        productCacheService.delete(buildProductDetailKey(productId));
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
