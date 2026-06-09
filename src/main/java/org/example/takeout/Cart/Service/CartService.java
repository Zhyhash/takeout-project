package org.example.takeout.Cart.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.takeout.Cart.DTO.DeleteDTO;
import org.example.takeout.Cart.DTO.UpdateCartDTO;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.VO.CartListVO;
import org.example.takeout.Cart.VO.CartVO;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Exception.AuthException;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class CartService {
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;

    //添加
    public CartVO add(AddCartDTO addCartDTO) {
        // 1. 校验商品是否存在
        Product product = productMapper.selectOne(Wrappers.<Product>lambdaQuery().
                eq(Product::getId, addCartDTO.getProductId()).
                ne(Product::getIsDeleted, DeleteConstant.DELETED).
                eq(Product::getStatus, ProductStatusEnum.ON_SALE.getCode()));
        if (product == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品不存在");
        }
        if (product.getStock() == null || product.getStock() <= 0){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"没有库存了，无法添加");
        }
        // 2. 获取当前用户 ID
        Long userId = UserContextHolder.getUserId();

        // 3. 查询该用户的购物车中是否已存在该商品
        // 这里不允许不同商家的order在一个购物车里面

        // TODO: v2.0 优化 - 当前采用“先查后判”存在并发漏洞和性能开销。
        // 未来计划：方案A-引入购物车主表建立user_id唯一索引；方案B-改用Redis存储商家绑定关系。
        // 1. 顺着正常人思维：直接查当前用户购物车里【第一条】已有的商品

        // 让数据库只数一下：有多少条记录的商家，和当前商品的商家不一样
        Long customConflictCount = cartMapper.selectCount(
                Wrappers.<CartItem>lambdaQuery()
                        .eq(CartItem::getUserId, userId)
                        .ne(CartItem::getMerchantId, product.getMerchantId())
        );

        // 只要有一个不一样的，直接拦截
        if (customConflictCount > 0) {
            //TODO：这里先抛出异常，到时候换成pay接口
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "只能加入同一家店的商品");
        }

        CartItem cartItem = cartMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getProductId, addCartDTO.getProductId()));

        if (cartItem == null) {
            // 情况 A：商品不存在于购物车 -> 创建新记录
            cartItem = new CartItem();
            cartItem.setUserId(userId);
            cartItem.setProductId(product.getId());
            cartItem.setProductName(product.getProductName());
            cartItem.setProductImage(product.getImageUrl());
            cartItem.setPrice(product.getPrice());
            cartItem.setMerchantId(product.getMerchantId());
            cartItem.setQuantity(1); // 初始数量为 1

            // 插入数据库（MyBatis-Plus 会自动将自增 ID 回填到 cartItem 对象中）
            cartMapper.insert(cartItem);
        } else {
            if(cartItem.getQuantity()==null){
                cartItem.setQuantity(0);
            }
            // 情况 B：商品已存在于购物车 -> 数量 +1 并更新
            if (cartItem.getQuantity()+1>product.getStock()) {
                //数量已经超过，不能继续增加
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"已经超过库存上限了，无法继续添加");
            }
            cartItem.setQuantity(cartItem.getQuantity() + 1);

            // 更新数据库
            cartMapper.updateById(cartItem);
        }

        // 4. 统一组装返回给前端的 CartVO

        return getCartVO(cartItem);
    }

    private static CartVO getCartVO(CartItem cartItem) {
        CartVO cartVO = new CartVO();
        cartVO.setId(cartItem.getId()); // 此时无论是新插入还是旧记录，都能拿到正确的购物车条目 ID
        cartVO.setProductId(cartItem.getProductId());
        cartVO.setProductName(cartItem.getProductName());
        cartVO.setProductImage(cartItem.getProductImage());
        cartVO.setPrice(cartItem.getPrice());
        cartVO.setQuantity(cartItem.getQuantity());

        // 计算小计：单价 * 数量 (假设价格类型为 BigDecimal)
        if (cartItem.getPrice() != null) {
            cartVO.setSubtotal(cartItem.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }
        return cartVO;
    }


    //修改某一个商品的数量
    public CartVO update(UpdateCartDTO updateCartDTO) {
        //查看DTO传入数据是否合法
        if (updateCartDTO.getQuantityChange()!=1 && updateCartDTO.getQuantityChange()!=-1)
            throw new AuthException("参数不合法");
        //查询数据库的购物车记录
        CartItem cartItem = cartMapper.selectOne(Wrappers.<CartItem>lambdaQuery().
                eq(CartItem::getId, updateCartDTO.getCartItemId()).
                eq(CartItem::getUserId,UserContextHolder.getUserId()));
        if (cartItem == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"这个要修改的项目不存在");
        }
        Integer quantity = cartItem.getQuantity();
        //如果存量<=1且修改量为-1，直接物理删除
        if (quantity <= 1&&updateCartDTO.getQuantityChange()==-1) {
            cartMapper.deleteById(cartItem.getId());
            // 组装一个数量为 0 的 VO 给前端，方便前端直接做行删除的动画
            CartVO cartVO = getCartVO(cartItem);
            cartVO.setQuantity(0);
            cartVO.setSubtotal(BigDecimal.ZERO);
            return cartVO;
        }else  {
            Product product = productMapper.selectById(cartItem.getProductId());
            if (product == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品不存在或已下架");
            }
            // 注意：购物车数量 +1 后的总量不能超过库存
            if (quantity + 1 > product.getStock()) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存不足，当前库存：" + product.getStock());
            }
            cartItem.setQuantity(updateCartDTO.getQuantityChange()+quantity);
            cartMapper.updateById(cartItem);
        }
        return getCartVO(cartItem);
    }

    public CartListVO list() {
        // 1. 直接查询出 CartItem 列表
        List<CartItem> cartItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, UserContextHolder.getUserId()));

        // 2. 判空处理
        if (cartItems == null || cartItems.isEmpty()) {

            CartListVO cartListVO = new CartListVO();
            cartListVO.setItems(new ArrayList<>());
            cartListVO.setTotalAmount(BigDecimal.ZERO);

            return cartListVO;
        }

        List<CartVO> list = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        // 将两个循环合并为一个，一边转换，一边计算总价
        for (CartItem cartItem : cartItems) {
            CartVO cartVO = getCartVO(cartItem);
            list.add(cartVO);

            if (cartVO.getSubtotal() != null) {
                totalAmount = totalAmount.add(cartVO.getSubtotal());
            }
        }

        // 4. 组装返回对象
        CartListVO cartListVO = new CartListVO();
        cartListVO.setItems(list);
        cartListVO.setTotalAmount(totalAmount);

        return cartListVO;
    }
    public void delete(DeleteDTO deleteDTO) {
        if (deleteDTO.getCartItemIds() == null || deleteDTO.getCartItemIds().isEmpty()) {
            return;
        }
        cartMapper.delete(Wrappers.<CartItem>lambdaQuery().
                in(CartItem::getId, deleteDTO.getCartItemIds()).
                eq(CartItem::getUserId, UserContextHolder.getUserId()));
    }
    public void clear(){
        //通过从 ThreadLocal 获取当前请求上下文里的 userId
        cartMapper.delete(Wrappers.<CartItem>lambdaQuery().
                eq(CartItem::getUserId, UserContextHolder.getUserId()));
    }
}
