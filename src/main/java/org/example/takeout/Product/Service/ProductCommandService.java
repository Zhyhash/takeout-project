package org.example.takeout.Product.Service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductCommandService {
    private final ProductMapper productMapper;
    private final ProductDetailCacheService productDetailCacheService;

    //对category模块 调用接口
    public void migrateProductsToCategory(Long merchantId, Long sourceCategoryId, Long targetCategoryId) {
        List<Long> productIds = productMapper.
                selectIdsByMerchantIdAndCategoryId(merchantId, sourceCategoryId);

        if (productIds.isEmpty()) {
            return;
        }

        int affected = productMapper.updateCategory(
                merchantId,
                sourceCategoryId,
                targetCategoryId
        );
        if (affected != productIds.size()) {
            throw new BusinessException(ResultCodeEnum.DATABASE_ERROR,
                    "商品分类迁移数量异常"
            );
        }
        for (Long productId : productIds) {
            productDetailCacheService.evictProductDetailCache(productId);
        }
    }

    //对orderItem模块 调用接口
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
                eq("is_deleted", DeleteConstant.NOT_DELETED).
                eq("status", ProductStatusEnum.ON_SALE.getCode())
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
    private void evictCacheIfInStockChanged(Long productId, int stockDelta) {
        // 订单退库允许更新逻辑删除商品，库存回读也必须绕过逻辑删除过滤，
        // 否则回读为空会抛异常并回滚已经完成的库存归还。
        Product updatedProduct = productMapper.selectStockByIdIncludingDeleted(productId);
        if (updatedProduct == null || updatedProduct.getStock() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存更新后商品信息异常");
        }

        long newStock = updatedProduct.getStock();
        long oldStock = newStock - stockDelta;
        boolean wasInStock = oldStock > 0;
        boolean isInStock = newStock > 0;

        if (wasInStock != isInStock) {
            productDetailCacheService.evictProductDetailCache(productId);
        }
    }


}
