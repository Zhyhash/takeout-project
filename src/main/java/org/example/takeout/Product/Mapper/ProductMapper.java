package org.example.takeout.Product.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Product.Entity.Product;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    // 只需要声明方法，入参用 @Param 标记，方便 XML 引用
    Integer restoreDeletedProduct(@Param("id") Long id,
                                  @Param("merchantId") Long merchantId,
                                  @Param("offSaleStatus") Integer offSaleStatus);

    @Select("""
            SELECT id, stock
            FROM product
            WHERE id = #{productId}
            """)
    Product selectStockByIdIncludingDeleted(@Param("productId") Long productId);

    @Update("""
            UPDATE product
            SET status = CASE
                    WHEN is_deleted = 0 AND status = #{saleOutStatus} THEN #{onSaleStatus}
                    ELSE status
                END,
                stock = stock + #{quantity},
                version = version + 1
            WHERE id = #{productId}
            """)
    int increaseStock(@Param("productId") Long productId,
                      @Param("quantity") Integer quantity,
                      @Param("saleOutStatus") Integer saleOutStatus,
                      @Param("onSaleStatus") Integer onSaleStatus);


}
