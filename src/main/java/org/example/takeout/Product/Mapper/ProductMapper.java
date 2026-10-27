package org.example.takeout.Product.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Product.Entity.Product;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    List<String> selectReferencedImageUrls(@Param("candidates") Collection<String> candidates);

    // 只需要声明方法，入参用 @Param 标记，方便 XML 引用
    Integer restoreDeletedProduct(@Param("id") Long id,
                                  @Param("merchantId") Long merchantId,
                                  @Param("offSaleStatus") Integer offSaleStatus);

    @Select("""
            SELECT id, stock, stock_version
            FROM product
            WHERE id = #{productId}
            """)
    Product selectStockByIdIncludingDeleted(@Param("productId") Long productId);

    /**
     * 锁定商品行并读取库存版本，供订单预扣库存时把版本快照与扣减放进同一事务/行锁区间。
     */
    @Select("""
            SELECT id, stock, stock_version, status, is_deleted
            FROM product
            WHERE id = #{productId}
            FOR UPDATE
            """)
    Product selectStockByIdForUpdate(@Param("productId") Long productId);

    /**
     * 取消订单回补库存：仅当订单明细记录的库存版本仍是当前基准时才执行，
     * 版本不一致时影响行数为 0，由调用方跳过回补。
     */
    @Update("""
            UPDATE product
            SET status = CASE
                    WHEN is_deleted = 0 AND status = #{saleOutStatus} THEN #{onSaleStatus}
                    ELSE status
                END,
                stock = stock + #{quantity},
                version = version + 1
            WHERE id = #{productId}
              AND stock_version = #{stockVersion}
            """)
    int increaseStock(@Param("productId") Long productId,
                      @Param("quantity") Integer quantity,
                      @Param("stockVersion") Integer stockVersion,
                      @Param("saleOutStatus") Integer saleOutStatus,
                      @Param("onSaleStatus") Integer onSaleStatus);

    @Update("""
            UPDATE product
            SET status = CASE
                    WHEN stock = #{quantity} THEN #{saleOutStatus}
                    ELSE status
                END,
                stock = stock - #{quantity},
                version = version + 1
            WHERE id = #{productId}
              AND stock_version = #{stockVersion}
              AND is_deleted = #{notDeleted}
              AND status = #{onSaleStatus}
              AND stock >= #{quantity}
            """)
    int decreaseStock(@Param("productId") Long productId,
                      @Param("quantity") Integer quantity,
                      @Param("stockVersion") Integer stockVersion,
                      @Param("notDeleted") Integer notDeleted,
                      @Param("onSaleStatus") Integer onSaleStatus,
                      @Param("saleOutStatus") Integer saleOutStatus);

    @Select("""
            SELECT id FROM product
            WHERE merchant_id=#{merchantId}
             AND category_id=#{categoryId}
            FOR UPDATE
    """)
    List<Long> selectIdsByMerchantIdAndCategoryId(
            @Param("merchantId") Long merchantId,
            @Param("categoryId") Long categoryId
    );

    @Update("""
    UPDATE product
        SET category_id = #{targetCategoryId},
            version=version+1
    WHERE merchant_id = #{merchantId}
        AND category_id=#{sourceCategoryId}
    """)
    int updateCategory(Long merchantId, Long sourceCategoryId, Long targetCategoryId);
}
