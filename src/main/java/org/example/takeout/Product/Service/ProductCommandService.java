package org.example.takeout.Product.Service;

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

/**
 * 商品模块面向其他模块的写操作入口。
 * 负责分类迁移、订单库存扣减与归还，并在需要时提交商品详情缓存失效请求。
 */
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
    /**
     * 预扣库存并返回本次扣减所对应的 stock_version。
     * 先以 FOR UPDATE 锁住商品行，再在同一事务内按该版本条件扣减，
     * 保证订单明细记录的不是准备阶段可能过期的商品快照版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public int decreaseStock(Long productId, Integer quantity){
        if (productId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品信息不能为空");
        }

        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "扣减数量必须大于0");
        }
        Product currentProduct = productMapper.selectStockByIdForUpdate(productId);
        if (currentProduct == null || currentProduct.getStockVersion() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"创建订单失败");
        }
        int row = productMapper.decreaseStock(
                productId,
                quantity,
                currentProduct.getStockVersion(),
                DeleteConstant.NOT_DELETED,
                ProductStatusEnum.ON_SALE.getCode(),
                ProductStatusEnum.SALE_OUT.getCode());
        if (row != 1)
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"创建订单失败");
        evictCacheIfInStockChanged(productId, -quantity);
        return currentProduct.getStockVersion();
    }

    /**
     * 取消订单回补库存。仅当订单明细版本仍等于当前库存基准时更新；
     * 版本不一致（商家已重设库存）时跳过回补并返回 false，不影响订单取消事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean increaseStock(Long productId, Integer quantity, Integer stockVersion) {
        if (productId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品信息不能为空");
        }
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"归还数量必须大于0");
        }
        if (stockVersion == null) {
            return false;
        }
        int rows = productMapper.increaseStock(
                productId,
                quantity,
                stockVersion,
                ProductStatusEnum.SALE_OUT.getCode(),
                ProductStatusEnum.ON_SALE.getCode());
        if (rows != 1) {
            return false;
        }
        evictCacheIfInStockChanged(productId, quantity);
        return true;
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
