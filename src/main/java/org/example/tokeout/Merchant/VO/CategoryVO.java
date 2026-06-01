package org.example.tokeout.Merchant.VO;

import lombok.Data;
import org.example.tokeout.Product.VO.ProductVO;

import java.util.List;

@Data
public class CategoryVO {
    private Long categoryCode;
    private String categoryName;
    private List<ProductVO> products;
}
