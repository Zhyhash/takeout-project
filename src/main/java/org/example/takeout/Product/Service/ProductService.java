package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Service.CategoryService;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.FileStorageException;
import org.example.takeout.Common.Exception.RedisCacheUnavailableException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Cache.ProductDetailCacheDTO;
import org.example.takeout.Product.DTO.CreateProductDTO;
import org.example.takeout.Product.DTO.UpdateProductDTO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductConverter;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.example.takeout.Product.VO.MerchantProductVO;
import org.example.takeout.Product.VO.ProductVO;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final long MAX_PRODUCT_IMAGE_SIZE = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png");

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png");
    private final ProductMapper productMapper;
    private final CategoryService  categoryService;
    private final ProductConverter productConverter;
    private final ProductDetailCacheService productDetailCacheService;

    public static final String DEFAULT_PRODUCT_IMAGE_URL = "/images/default-product.svg";

    private static final String ACTIVE_PRODUCT_NAME_CONFLICT_MESSAGE = "当前店铺已存在同名商品";



    //NOTE:抽取方法，转换Product
    public Product createProductEntity(CreateProductDTO createProductDTO){
        Product product = productConverter.toProduct(createProductDTO, MerchantContextHolder.getMerchantId());
        product.setImageUrl(resolveImageUrl(product.getImageUrl()));
        product.setStatus(ProductStatusEnum.OFF_SALE.getCode());
        product.setIsDeleted(DeleteConstant.NOT_DELETED);
        return product;
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return DEFAULT_PRODUCT_IMAGE_URL;
        }
        return imageUrl.trim();
    }


    //NOTE:创建商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO createProduct(@NonNull CreateProductDTO createProductDTO) {
        Long merchantId = MerchantContextHolder.getMerchantId();
        Category category = categoryService.lockActiveCategory(createProductDTO.getCategoryId(),
                merchantId);
        if (category == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"种类不存在");
        }
        Product product = createProductEntity(createProductDTO);
        product.setVersion(0);
        try {
            productMapper.insert(product);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    ACTIVE_PRODUCT_NAME_CONFLICT_MESSAGE
            );
        }
        return productConverter.toMerchantProductVO(product, category);
    }

    public MerchantProductVO getMerchantProductDetail(Long productId) {
        Product product = getProduct(productId);
        if (product == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在或不属于当前商家");
        }
        return productConverter.toMerchantProductVO(product, categoryService.getCategory(product.getCategoryId()));
    }

    //NOTE:上架商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO onShelf(Long productId){
        Product product = getProduct(productId);
        validateShelfChangeLegal(product, ProductStatusEnum.ON_SALE);
        Category category = categoryService.getCategory(product.getCategoryId());
        if (product.getStock() == null || product.getStock() < 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存异常，无法上架");
        }
        ProductStatusEnum targetStatus = product.getStock() > 0
                ? ProductStatusEnum.ON_SALE
                : ProductStatusEnum.SALE_OUT;

        if (!targetStatus.getCode().equals(product.getStatus())) {
            changeProductStatus(product, targetStatus);
        }
        productDetailCacheService.evictProductDetailCache(productId);

        return productConverter.toMerchantProductVO(product, category);
    }

    //NOTE:下架商品
    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO offShelf(Long productId){
        Product product = getProduct(productId);
        validateShelfChangeLegal(product, ProductStatusEnum.OFF_SALE);
        Category category = categoryService.getCategory(product.getCategoryId());

        if (!ProductStatusEnum.OFF_SALE.getCode().equals(product.getStatus())) {
            changeProductStatus(product, ProductStatusEnum.OFF_SALE);
        }
        productDetailCacheService.evictProductDetailCache(productId);

        return productConverter.toMerchantProductVO(product, category);
    }

    public ProductVO getProductDetail(Long productId){
        ProductDetailCacheDTO productDetailCache;
        try {
            productDetailCache = productDetailCacheService.getProductDetailCache(productId,
                    ()->loadProductDetailFromMysqlOnly(productId));
        } catch (RedisCacheUnavailableException e) {
            log.warn(
                    "Redis不可用，商品详情降级查询MySQL，productId={}",
                    productId,
                    e
            );
            productDetailCache =
                    loadProductDetailFromMysqlOnly(productId);
        }

        if (productDetailCache == null ||
                !Objects.equals(
                        productDetailCache.getMerchantId(),
                        MerchantContextHolder.getMerchantId())) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在或不属于当前商家"
            );
        }
        return productConverter.toProductVO(productDetailCache);
    }



    @Transactional(rollbackFor = Exception.class)
    public MerchantProductVO updateProduct(Long productId,@NonNull UpdateProductDTO updateProductDTO) {
        Product updatedProduct = updateProductAndEvictCache(productId, updateProductDTO);
        return productConverter.toMerchantProductVO(updatedProduct, categoryService.getCategory(updatedProduct.getCategoryId()));
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
        productDetailCacheService.evictProductDetailCache(productId);
    }

    private Product updateProductAndEvictCache(Long productId, UpdateProductDTO updateProductDTO){
        Long merchantId = MerchantContextHolder.getMerchantId();
        if (updateProductDTO.getCategoryId() != null) {
            Category category = categoryService.lockActiveCategory(updateProductDTO.getCategoryId(),
                    merchantId);

            if (category == null) {
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
        productDetailCacheService.evictProductDetailCache(productId);
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

    private ProductDetailCacheDTO loadProductDetailFromMysqlOnly(Long productId) {
        Product product = productMapper.selectById(productId);

        if (product == null) {
            return null;
        }

        return productConverter.toProductDetailCacheDTO(product);
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
        return productPage.convert(product -> productConverter.toMerchantProductVO(product, categoryMap.get(product.getCategoryId())));
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

        return categoryService.getCategoryMap(categoryIds,
                MerchantContextHolder.getMerchantId());
    }

    // 恢复逻辑删除的商品；active 唯一键负责裁决同名冲突。
    @Transactional(rollbackFor = Exception.class)
    public void restoreProduct(@NotNull Long productId) {
        Long merchantId = MerchantContextHolder.getMerchantId();
        int rows;
        try {
            rows = productMapper.restoreDeletedProduct(
                    productId,
                    merchantId,
                    ProductStatusEnum.OFF_SALE.getCode()
            );
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    ACTIVE_PRODUCT_NAME_CONFLICT_MESSAGE
            );
        }
        if (rows != 1) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "商品不存在、未删除或不属于当前商家"
            );
        }
        productDetailCacheService.evictProductDetailCache(productId);
    }

    public String uploadImage(MultipartFile file) {
        if (file.isEmpty()) {
            return null;
        }
        if (file.getSize() > MAX_PRODUCT_IMAGE_SIZE){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "图片大小不得超过5MB");
        }
        if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(file.getContentType())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "不支持的类型");
        }
        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "不支持的类型");
        }

        BufferedImage image;

        try (InputStream inputStream = file.getInputStream()) {
            image = ImageIO.read(inputStream);
        } catch (IOException e) {
            throw new FileStorageException(e.getMessage(),e);
        }

        if (image == null) {
            throw new FileStorageException("上传格式不支持");
        }

        String filename = UUID.randomUUID()+ "." + extension;

        Path uploadDir = Paths.get("./uploads/products");
        try {
            Files.createDirectories(uploadDir);
            Path targetPath = uploadDir.resolve(filename);

            file.transferTo(targetPath);
        } catch (IOException e) {
            throw new FileStorageException(e.getMessage(),e);
        }

        return "/uploads/products/" + filename;
    }
    private String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }
}
