package org.example.takeout.Product.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.VO.MerchantProductVO;

import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    // 只需要声明方法，入参用 @Param 标记，方便 XML 引用
    List<MerchantProductVO> listMerchantProducts(
            @Param("merchantId") Long merchantId,
            @Param("status") Integer status,
            @Param("categoryId") Long categoryId
    );

    Integer restoreDeletedProduct(@Param("id") Long id,
                              @Param("merchantId") Long merchantId);
}
