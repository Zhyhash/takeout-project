package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

//NOTE:校验类
@Service
public class OrderDomainService {
    //NOTE:全局方法：数据库校验抽取
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private ProductMapper productMapper;
    public Order getOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId));
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"订单不存在或状态不符");
        }
        return order;
    }
    //NOTE:聚合函数，负责拿到productSummary商品简介
    public String buildProductSummary(List<OrderItem> items){
        //从items里面拿到商品
        if (items==null||items.isEmpty())
            return "";
        int sum = items.stream().mapToInt(OrderItem::getQuantity).sum();
        String firstProductName = items.get(0).getProductName();
        if (sum==1)
            return firstProductName;
        return String.format("%s 等 %d 件商品", firstProductName, sum);
    }
    //NOTE:制造orderNo方法
    public @NonNull String createOrderNo(){
        return "ORD" + System.currentTimeMillis() +
                UUID.randomUUID().toString().substring(0, 4);
    }

    //NOTE:计算金额方法
    BigDecimal calculateTotalAmount(@NonNull List<CartItem> cartItems, Map<Long, Product> productMap) {
        return cartItems.stream().
                //peek 的语义是“检查/观察”：它的设计初衷是在不改变流中元素的情况下，对元素进行某种动作
                        //换句话说，这里只是在做防御性校验，数据本身不会在这里被转换
                peek(cartItem -> {
                    if (cartItem.getProductId()==null||cartItem.getQuantity()==null||cartItem.getQuantity()<=0){
                        throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"计价失败：购物车明细数据不完整");
                    }
                })
                .map(item -> {
                    Product product = productMap.get(item.getProductId());
                    if (product==null){
                        throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "计价失败：商品信息不存在或已下架");
                    }
                    if (product.getPrice()==null){
                        throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "计价失败：系统检测到异常商品价格，请联系客服");
                    }
                    return product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                }).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
