package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Service.CartCheckoutService;
import org.example.takeout.Cart.Service.CartService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Order.Assembler.OrderVOAssembler;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Order.VO.OrderDetailVO;
import org.example.takeout.Order.VO.OrderVO;
import org.example.takeout.Product.Entity.Product;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderTransactionExecutor orderTransactionExecutor;
    private final OrderItemService orderItemService;
    private final OrderItemMapper orderItemMapper;
    private final OrderMapper orderMapper;
    private final OrderVOAssembler orderVOAssembler;
    private final OrderConvertor orderConvertor;
    private final CartCheckoutService CartCheckoutService;
    private final OrderRequestFingerprint orderRequestFingerprint;
    private final BigDecimal MAX_ORDER_AMOUNT= BigDecimal.valueOf(1_000_000);

    public CreateOrderVO createOrder(CreateOrderDTO dto) {
        Long userId = UserContextHolder.getUserId();
        String requestHash = orderRequestFingerprint.calculate(dto);

        // 第一次幂等检查
        Order existing = findOrder(userId, dto.getRequestId());
        if (existing != null) {
            validateRequestHash(existing, requestHash);
            return orderConvertor.toCreateOrderVO(existing);
        }

        OrderDataContext context;
        try {
            context = prepareOrderDataContext(userId);
        } catch (BusinessException e) {
            if (!Objects.equals(e.getCode(), ResultCodeEnum.CART_EMPTY.getCode())) {
                throw e;
            }
            // prepare 失败后的第二次幂等检查
            existing = findOrder(userId, dto.getRequestId());

            if (existing != null) {
                validateRequestHash(existing, requestHash);
                return orderConvertor.toCreateOrderVO(existing);
            }

            throw e;
        }
        Order order = orderTransactionExecutor.executeOrderCreation(
                context, dto, userId, requestHash
        );

        return orderConvertor.toCreateOrderVO(order);
    }
    private Order findOrder(Long userId, String requestId) {
        return orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getRequestId, requestId)
        );
    }
    private void validateRequestHash(Order order, String requestHash) {
        if (!Objects.equals(order.getRequestHash(), requestHash)) {
            throw new BusinessException(
                    ResultCodeEnum.PARAM_ERROR,
                    "同一 requestId 不能携带不同下单参数，请使用新的 requestId"
            );
        }
    }
    private OrderDataContext prepareOrderDataContext(Long userId) {
        // 获取可用购物车（内部已校验商品/商家状态）
        CartAvailableResult result = CartCheckoutService.prepareCheckout(userId);

        List<CartItem> availableCartItems = getCartItems(result);

        if (result.getMerchantId() == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商家不存在");
        }

        Map<Long, Product> productMap = result.getProductMap();


        BigDecimal totalAmount = calculateTotalAmount(availableCartItems, productMap);
        if (totalAmount.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "商品总金额超过最大结算数，请清理购物车");
        }

        OrderDataContext orderDataContext = new OrderDataContext();
        orderDataContext.setTotalAmount(totalAmount);
        orderDataContext.setMerchantId(result.getMerchantId());
        orderDataContext.setMerchantName(result.getMerchantName());
        orderDataContext.setProductMap(productMap);
        orderDataContext.setAvailableItems(availableCartItems);
        return orderDataContext;
    }

    private  @NonNull List<CartItem> getCartItems(CartAvailableResult result) {
        List<CartItem> allCartItems = result.getAllItems();
        List<CartItem> availableCartItems = result.getAvailableItems();
        if (allCartItems == null || allCartItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.CART_EMPTY, "购物车为空，无法创建订单");
        }
        if (availableCartItems == null || availableCartItems.size() != allCartItems.size()) {
            throw new BusinessException(ResultCodeEnum.CART_EMPTY,
                    "购物车包含不可用商品或不可下单商家，请刷新购物车后重试");
        }
        return availableCartItems;
    }

    //NOTE:计算金额方法
    private BigDecimal calculateTotalAmount(@NonNull List<CartItem> cartItems, Map<Long, Product> productMap) {
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




    //NOTE:查询单个id订单
    /**
     * orderId:表示订单的id
     * */
    public OrderDetailVO searchOrderDetailById(@NonNull Long orderId){
        Long userId = UserContextHolder.getUserId();
        //只查询订单，不需要状态机
        Order order = getUserOrder(orderId, userId);

        //查询item
        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, order.getId()));

        if (orderItems == null || orderItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单子项异常，查询失败");
        }

        //返回组装好的详情对象
        return orderVOAssembler.toOrderDetailVO(order, orderItems);
    }
    private Order getUserOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId));
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"订单不存在或状态不符");
        }
        return order;
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


        return pageInfo.convert(order -> orderVOAssembler.toOrderVO(order, itemsMap));

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

        restoreOrderStock(orderId);
    }

    private void restoreOrderStock(Long orderId) {
        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().
                eq(OrderItem::getOrderId, orderId));
        if (orderItems == null || orderItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单明细为空，无法取消订单");
        }
        orderItemService.increaseStocksOrderedByProductId(orderItems);
    }

    //NOTE:模拟支付
    private boolean paying(){return true;}
    //NOTE:单个订单支付
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        int i = orderMapper.updateOrderStatusToPaying(orderId, userId,
                OrderStatusEnum.WAIT_PAY.getCode(), OrderStatusEnum.PAYING.getCode(),
                LocalDateTime.now().minusMinutes(30));
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
                OrderStatusEnum.DELIVERED.getCode(),
                OrderStatusEnum.FINISHED.getCode(),LocalDateTime.now());
        if (i != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"订单不存在或当前状态不可确认");
        }
    }

    private List<Order> findMerchantOrdersByStatuses(Long merchantId, List<Integer> statuses) {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getMerchantId, merchantId)
                .in(Order::getStatus, statuses)
                .orderByDesc(Order::getCreateTime));
    }

    // 以下查询接口提供给商家领域使用。
    public List<Order> findMerchantPendingOrders(@NonNull Long merchantId) {
        return findMerchantOrdersByStatuses(merchantId, List.of(OrderStatusEnum.PAID.getCode()));
    }

    public List<Order> findMerchantProcessingOrders(@NonNull Long merchantId) {
        return findMerchantOrdersByStatuses(merchantId, List.of(OrderStatusEnum.PREPARING.getCode()));
    }

    public List<Order> findMerchantHistoryOrders(@NonNull Long merchantId) {
        return findMerchantOrdersByStatuses(merchantId, List.of(
                OrderStatusEnum.READY.getCode(),
                OrderStatusEnum.DELIVERING.getCode(),
                OrderStatusEnum.DELIVERED.getCode(),
                OrderStatusEnum.FINISHED.getCode()));
    }

    public Order findMerchantOrderById(@NonNull Long orderId, @NonNull Long merchantId) {
        return orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getMerchantId, merchantId));
    }

    // 以下订单配送状态校验接口提供给 配送任务 领域使用。
    public void assertDelivering(Long orderId) {
        assertOrderStatus(orderId, OrderStatusEnum.DELIVERING.getCode(), "配送中");
    }

    public void assertOrderReachedDeliveryCompletion(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在");
        }
        if (!OrderStatusEnum.DELIVERED.getCode().equals(order.getStatus())
                && !OrderStatusEnum.FINISHED.getCode().equals(order.getStatus())) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于已送达或已完成状态，当前状态码：" + order.getStatus());
        }
    }

    private void assertOrderStatus(Long orderId, Integer expectedStatus, String expectedStatusDescription) {
        Order order = orderMapper.selectById(orderId);

        if (order == null) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "订单不存在"
            );
        }

        if (!expectedStatus.equals(order.getStatus())) {
            throw new BusinessException(
                    ResultCodeEnum.BUSINESS_ERROR,
                    "订单未处于" + expectedStatusDescription + "状态，当前状态码：" + order.getStatus()
            );
        }
    }

    // 以下超时取消接口提供给订单定时任务使用。
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTimeoutOrder(Long orderId, LocalDateTime expiredBefore) {
        int rows = orderMapper.updateTimeoutOrderToCancelled(
                orderId,
                OrderStatusEnum.WAIT_PAY.getCode(),
                OrderStatusEnum.CANCELLED.getCode(),
                expiredBefore
        );
        if (rows != 1) {
            return false;
        }
        restoreOrderStock(orderId);
        return true;
    }
}

