package org.example.takeout.Cart.VO;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

//返回整个购物车列表的VO
@Data
public class CartListVO {
    private List<CartVO> items;

    private BigDecimal totalAmount;
}
