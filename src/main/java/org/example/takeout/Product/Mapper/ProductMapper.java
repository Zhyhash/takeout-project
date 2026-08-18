package org.example.takeout.Product.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.takeout.Product.Entity.Product;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    // 只需要声明方法，入参用 @Param 标记，方便 XML 引用
    Integer restoreDeletedProduct(@Param("id") Long id,
                              @Param("merchantId") Long merchantId);

    @Update("UPDATE product SET stock = stock + #{quantity} WHERE id = #{productId}")
    int increaseStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);


}
