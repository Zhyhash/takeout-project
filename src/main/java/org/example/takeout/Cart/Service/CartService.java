package org.example.takeout.Cart.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Cart.DTO.AddCartDTO;
import org.example.takeout.Cart.DTO.DeleteDTO;
import org.example.takeout.Cart.DTO.UpdateCartDTO;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.VO.CartListVO;
import org.example.takeout.Cart.VO.CartVO;
import org.example.takeout.Common.Constants.DeleteConstant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Enums.MerchantStatusEnum;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.StatesEnum.ProductStatusEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CartService {
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private MerchantMapper merchantMapper;
    //添加
    @Transactional(rollbackFor = Exception.class)
    public CartVO add(AddCartDTO addCartDTO) {
        // 1. 校验商品是否存在
        Product product = productMapper.selectOne(Wrappers.<Product>lambdaQuery().
                eq(Product::getId, addCartDTO.getProductId()).
                ne(Product::getIsDeleted, DeleteConstant.DELETED).
                eq(Product::getStatus, ProductStatusEnum.ON_SALE.getCode()));
        if (product == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品不存在");
        }

        //判断商家状态是否营业
        Long merchantId = product.getMerchantId();
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null ||
                Objects.equals(merchant.getStatus(), MerchantStatusEnum.BUSINESS_CLOSED.getCode())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商家不存在或者商家已打烊");
        }

        //提升用户体验，提前拦截明显不可购买商品。
        if (product.getStock() == null || product.getStock() <= 0){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"没有库存了，无法添加");
        }

        Long userId = UserContextHolder.getUserId();

        // 查询该用户的购物车中是否已存在该商品
        // 这里不允许不同商家的order在一个购物车里面
        // 让数据库只数一下：有多少条记录的商家，和当前商品的商家不一样
        Long customConflictCount = cartMapper.selectCount(
                Wrappers.<CartItem>lambdaQuery()
                        .eq(CartItem::getUserId, userId)
                        .ne(CartItem::getMerchantId, product.getMerchantId())
        );

        // 只要有一个不一样的，直接拦截
        if (customConflictCount > 0) {
            //NOTE：这里先抛出异常，到时候换成pay接口
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "只能加入同一家店的商品");
        }

        CartItem cartItem = cartMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getProductId, addCartDTO.getProductId()));

        if (cartItem == null) {
            // 1. 如果不存在，创建新对象并完整赋值
            cartItem = new CartItem();
            cartItem.setUserId(userId);
            cartItem.setProductId(product.getId());
            cartItem.setProductName(product.getProductName());
            cartItem.setProductImage(product.getImageUrl());
            cartItem.setPrice(product.getPrice());
            cartItem.setMerchantId(product.getMerchantId());
            cartItem.setQuantity(1);
        }
        int i = cartMapper.addOrIncrease(cartItem);
        // MySQL INSERT ... ON DUPLICATE KEY UPDATE:
        // 1: 新增成功
        // 2: 更新成功
        // 0: 未产生变化
        if (i <= 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"购物车数量增加失败");
        }
        //返回值也要更新
        if (i==2)
            cartItem.setQuantity(cartItem.getQuantity() + 1);

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
        if (cartItem.getPrice() == null || cartItem.getQuantity() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"价格或数量为空");
        }
        cartVO.setSubtotal(cartItem.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        return cartVO;
    }


    //修改某一个商品的数量
    @Transactional(rollbackFor = Exception.class)
    public CartVO update(UpdateCartDTO updateCartDTO) {
        // 1. 参数校验：只允许 +1 或 -1
        Integer quantityChange = updateCartDTO.getQuantityChange();
        if (!Integer.valueOf(1).equals(quantityChange) && !Integer.valueOf(-1).equals(quantityChange)) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "修改的数量只能为-1或1");
        }

        // 2. 查询购物车记录（带用户ID，防止越权）
        CartItem cartItem = cartMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getId, updateCartDTO.getCartItemId())
                .eq(CartItem::getUserId, UserContextHolder.getUserId()));
        if (cartItem == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "购物车商品不存在");
        }

        // 3. 校验商家状态（打烊则不可修改）
        Merchant merchant = merchantMapper.selectById(cartItem.getMerchantId());
        if (merchant == null || Objects.equals(merchant.getStatus(), MerchantStatusEnum.BUSINESS_CLOSED.getCode())) {
            // 注意：这里不删除购物车记录，只抛异常。前端收到这个错误码后应主动刷新购物车列表
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "该商家已打烊，无法修改");
        }

        // 4. 校验商品状态
        Product product = productMapper.selectById(cartItem.getProductId());
        if (product == null || !Objects.equals(product.getStatus(), ProductStatusEnum.ON_SALE.getCode())) {
            // 同样不删除，只抛异常。前端刷新列表时会自动清理这条失效记录
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品已下架或不存在");
        }

        // 5. 计算新数量
        int oldQuantity = cartItem.getQuantity();
        int change = updateCartDTO.getQuantityChange();
        int newQuantity = oldQuantity + change;

        // 6. 如果新数量 <= 0，物理删除（此时所有前置校验已通过，事务安全）
        if (newQuantity <= 0) {
            int rows = cartMapper.delete(
                    Wrappers.<CartItem>lambdaQuery()
                            .eq(CartItem::getId,cartItem.getId())
                            .eq(CartItem::getVersion,cartItem.getVersion())
            );
            if (rows != 1) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                        "购物车删除失败");
            }
            // 返回一个数量为0的VO，让前端做删除动画
            CartVO emptyVO = getCartVO(cartItem);
            emptyVO.setQuantity(0);
            emptyVO.setSubtotal(BigDecimal.ZERO);
            return emptyVO;
        }



        // 8. 执行更新
        cartItem.setQuantity(newQuantity);
        int i = cartMapper.updateById(cartItem);
        if (i != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"购物车更新失败");
        }


        // 9. 返回更新后的VO
        return getCartVO(cartItem);
    }

    public CartListVO list() {
        Long userId = UserContextHolder.getUserId();

        boolean canBuy = true;
        String invalidReason = "";

        List<CartItem> cartItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId));
        if (cartItems == null || cartItems.isEmpty()) {
            return emptyCartListVO();
        }


        // 批量查询商品和商家
        List<Long> productIds = cartItems.stream().map(CartItem::getProductId).distinct().toList();
        List<Long> merchantIds = cartItems.stream().map(CartItem::getMerchantId).distinct().toList();

        if (merchantIds.size() > 1) {
            canBuy = false;
            invalidReason+="用户购物车有多商家\n";
        }
        Map<Long, Product> productMap = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .in(Product::getId, productIds))
                .stream().collect(Collectors.toMap(Product::getId, p -> p));
        Map<Long, Merchant> merchantMap = merchantMapper.selectList(Wrappers.<Merchant>lambdaQuery()
                        .in(Merchant::getId, merchantIds))
                .stream().collect(Collectors.toMap(Merchant::getId, m -> m));

        // 分类收集
        List<CartVO> allItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CartItem item : cartItems) {
            Product product = productMap.get(item.getProductId());
            Merchant merchant = merchantMap.get(item.getMerchantId());

            boolean productValid = (product != null && ProductStatusEnum.ON_SALE.getCode().equals(product.getStatus()));
            boolean merchantOpen = (merchant != null && !MerchantStatusEnum.BUSINESS_CLOSED.getCode().equals(merchant.getStatus()));
            CartVO vo = getCartVO(item);

            if (!merchantOpen) {
                vo.setAvailable(false);
                vo.setDisableReason("商家已打烊");

                canBuy = false;
                if (!invalidReason.contains("商家已打烊\n"))
                    invalidReason += "商家已打烊\n";
            }else if (!productValid) {
                canBuy = false;
                vo.setAvailable(false);
                vo.setDisableReason("商品无效");
                if (!invalidReason.contains("商品不存在或状态无效\n"))
                    invalidReason += "商品不存在或状态无效\n";
            } else {
                vo.setAvailable(true);
                // 只有可用商品才计入总价
                totalAmount = totalAmount.add(vo.getSubtotal());
            }
            allItems.add(vo);
        }



        CartListVO result = new CartListVO();
        result.setItems(allItems);
        result.setTotalAmount(totalAmount);
        result.setCanBuy(canBuy);
        result.setInvalidReason(invalidReason);

        return result;
    }

    // 辅助方法：返回空购物车
    private CartListVO emptyCartListVO() {
        CartListVO empty = new CartListVO();
        empty.setItems(Collections.emptyList());
        empty.setTotalAmount(BigDecimal.ZERO);
        empty.setCanBuy(false);
        empty.setInvalidReason("购物车为空");
        return empty;
    }
    @Transactional(rollbackFor = Exception.class)
    public void delete(DeleteDTO deleteDTO) {
        if (deleteDTO.getCartItemIds() == null || deleteDTO.getCartItemIds().isEmpty()) {
            return;
        }
        int deletedRows = cartMapper.delete(Wrappers.<CartItem>lambdaQuery().
                in(CartItem::getId, deleteDTO.getCartItemIds()).
                eq(CartItem::getUserId, UserContextHolder.getUserId()));


        if(deletedRows != deleteDTO.getCartItemIds().size()) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,"购物车清理失败"
            );
        }
    }
    @Transactional(rollbackFor = Exception.class)
    public void clear(){
        //通过从 ThreadLocal 获取当前请求上下文里的 userId
        cartMapper.delete(Wrappers.<CartItem>lambdaQuery().
                eq(CartItem::getUserId, UserContextHolder.getUserId()));
    }
}
