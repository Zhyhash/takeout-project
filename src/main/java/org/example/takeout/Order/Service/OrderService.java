package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.Service.cartDomainService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Order.VO.OrderDetailVO;
import org.example.takeout.Order.VO.OrderVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderService {
    @Autowired
    private OrderDomainService orderDomainService;
    @Autowired
    private OrderTransactionExecutor orderTransactionExecutor;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderVOBuilder orderVOBuilder;
    @Autowired
    private cartDomainService cartDomainService;



    public CreateOrderVO createOrder(@NonNull CreateOrderDTO createOrderDTO) {
        Long userId = UserContextHolder.getUserId();

        Order existing = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().
                        eq(Order::getUserId, userId).
                        eq(Order::getRequestId, createOrderDTO.getRequestId()));
        if (existing != null) {
            return orderVOBuilder.toCreateOrderVO(existing);
        }


        OrderDataContext orderDataContext = prepareOrderDataContext(createOrderDTO, userId);


        /// 修改数据库层面
        Order order = orderTransactionExecutor.executeOrderCreation(orderDataContext, createOrderDTO, userId);

        return orderVOBuilder.toCreateOrderVO(order);
    }
    private OrderDataContext prepareOrderDataContext(CreateOrderDTO createOrderDTO, Long userId) {
        // 获取可用购物车（内部已校验商品/商家状态）
        CartAvailableResult result = cartDomainService.getAvailableCartItems(userId);

        List<CartItem> availableCartItems = result.getAvailableItems();
        if (availableCartItems == null || availableCartItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "购物车为空，无法创建订单");
        }

        // 防脏数据：过滤多商家情况
        Map<Long, Merchant> merchantMap = result.getMerchantMap();
        Set<Long> merchantIds = availableCartItems.stream()
                .map(CartItem::getMerchantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (merchantIds.size() != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "购物车包含多个商家的商品，请清空后重试");
        }
        Long merchantId = merchantIds.iterator().next();

        Merchant merchant = merchantMap.get(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商家不存在");
        }

        Map<Long, Product> productMap = result.getProductMap();


        BigDecimal totalAmount = orderDomainService.calculateTotalAmount(availableCartItems, productMap);

        OrderDataContext orderDataContext = new OrderDataContext();
        orderDataContext.setTotalAmount(totalAmount);
        orderDataContext.setMerchant(merchant);
        orderDataContext.setProductMap(productMap);
        orderDataContext.setAvailableItems(availableCartItems);
        return orderDataContext;
    }




    //NOTE:查询单个id订单
    /**
     * orderId:表示订单的id
     * */
    public OrderDetailVO searchOrderDetailById(@NonNull Long orderId){
        Long userId = UserContextHolder.getUserId();
        //只查询订单，不需要状态机
        Order order = orderDomainService.getOrder(orderId, userId);

        //查询item
        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, order.getId()));

        if (orderItems == null || orderItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单子项异常，查询失败");
        }

        //返回组装好的详情对象
        return orderVOBuilder.toOrderDetailVO(order, orderItems);
    }


    //NOTE：分页查询
    public PageInfo<OrderVO> listOrders(Integer pageNum, Integer pageSize){
        Long userId = UserContextHolder.getUserId();
        PageHelper.startPage(pageNum, pageSize);
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery().
                eq(Order::getUserId, userId).orderByDesc(Order::getCreateTime));

        if (orders==null||orders.isEmpty())
            return new PageInfo<>(Collections.emptyList());

        List<Long> orderIds=orders.stream().map(Order::getId).toList();

        List<OrderItem> allItems = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery()
                        .in(OrderItem::getOrderId, orderIds)   // 使用 in，不是 eq
        );

        Map<Long, List<OrderItem>> itemsMap = allItems.stream().
                collect(Collectors.groupingBy(OrderItem::getOrderId));

        PageInfo<Order> pageInfo = new PageInfo<>(orders);


        return pageInfo.convert(order -> orderVOBuilder.toOrderVO(order, itemsMap));

    }
    //NOTE:取消订单，统一返回result
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        //检查订单id是否存在并直接关系
        ArrayList<Integer> canCancelList =new ArrayList<>(
                List.of(OrderStatusEnum.WAIT_PAY.getCode())
        );
        int rows = orderMapper.UpdateOrderStatusToCancel(orderId, userId, canCancelList, OrderStatusEnum.CANCELLED.getCode());
        if (rows != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"订单不存在或当前状态不可取消");
        }


        //取消了之后需要将库存还回去
        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().
                eq(OrderItem::getOrderId, orderId));
        if (orderItems == null || orderItems.isEmpty()) {
            return;
        }
        List<Long> productIds = orderItems.stream()
                .map(OrderItem::getProductId)
                .filter(Objects::nonNull)
                .toList();

        List<Product> products = productMapper.selectBatchIds(productIds);

        Map<Long, Product> productMap = products == null ? Collections.emptyMap() :
                products.stream().collect(Collectors.toMap(Product::getId, p -> p, (k1, k2) -> k1));

        for (OrderItem item : orderItems) {
            if (!productMap.containsKey(item.getProductId())) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商品不存在或已被物理删除");
            }

            int i = productMapper.increaseStock(item.getProductId(), item.getQuantity());
            if (i!=1) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"归还库存失败，商品可能处于异常状态");
            }
        }
    }

    //NOTE:模拟支付
    private boolean paying(){return true;}
    //NOTE:单个订单支付
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        int i = orderMapper.updateOrderStatusToPaying(orderId, userId,
                OrderStatusEnum.WAIT_PAY.getCode(), OrderStatusEnum.PAYING.getCode());
        if (i!=1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单不存在或当前状态不可支付");
        }
        if (paying()){
            int row = orderMapper.updateOrderStatusToPaid(orderId, userId,
                    OrderStatusEnum.PAYING.getCode(),
                    OrderStatusEnum.PAID.getCode(),LocalDateTime.now());
            if (row!=1) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或当前状态不可支付");
            }
        }else {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或当前状态不可支付");
        }
    }

    //NOTE:用户点击确认查收
    @Transactional(rollbackFor = Exception.class)
    public void checkedOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();

        int i = orderMapper.updateOrderStatusToFinished(orderId,userId,
                OrderStatusEnum.PAID.getCode(),
                OrderStatusEnum.FINISHED.getCode(),LocalDateTime.now());
        if (i != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"订单不存在或当前状态不可确认");
        }
    }
}

