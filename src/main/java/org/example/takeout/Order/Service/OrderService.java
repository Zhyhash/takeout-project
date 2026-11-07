package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Cart.Domain.CartAvailableResult;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Service.CartCheckoutService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Common.Utils.GeoDistanceUtils;
import org.example.takeout.Merchant.Service.MerchantSseService;
import org.example.takeout.Order.Assembler.OrderVOAssembler;
import org.example.takeout.Order.DTO.CreateOrderDTO;
import org.example.takeout.Order.Domain.DeliveryFeeCalculator;
import org.example.takeout.Order.Domain.OrderDataContext;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderCancelSourceEnum;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Support.OrderRequestFingerprint;
import org.example.takeout.Order.VO.CreateOrderVO;
import org.example.takeout.Order.VO.OrderDetailVO;
import org.example.takeout.Order.VO.OrderVO;
import org.example.takeout.Product.Entity.Product;
import org.example.takeout.Settlement.Service.SettlementService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单业务应用服务及对外订单能力入口。
 * 负责用户下单、支付、取消、签收和查询，并协调下单事务、库存归还与支付后通知。
 * 为商家提供订单查询与已读维护，为配送流程提供订单状态校验，并处理超时取消。
 */
@Service
@RequiredArgsConstructor
public class OrderService {
    @Value("${payment.stripe.enabled:false}")
    private boolean stripePaymentsEnabled;
    private final OrderTransactionExecutor orderTransactionExecutor;
    private final OrderItemService orderItemService;
    private final OrderItemMapper orderItemMapper;
    private final OrderMapper orderMapper;
    private final OrderVOAssembler orderVOAssembler;
    private final OrderConvertor orderConvertor;
    private final CartCheckoutService CartCheckoutService;
    private final OrderRequestFingerprint orderRequestFingerprint;
    private final BigDecimal MAX_ORDER_AMOUNT= BigDecimal.valueOf(1_000_000);
    private final MerchantSseService merchantSseService;
    private final DeliveryFeeCalculator deliveryFeeCalculator;
    private final SettlementService settlementService;


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
            context = prepareOrderDataContext(userId,dto);
        } catch (BusinessException e) {
            if (!Objects.equals(e.getCode(), ResultCodeEnum.CART_EMPTY.getCode())) {
                throw e;
            }
            // prepare 失败后的第二次幂等检查
            //这里是为了避免以下时序：
            //B 第一次 findOrder 查不到订单，此时 A 尚未提交。
            //A 下单成功，创建订单和消费购物车一起提交。
            //B 执行 prepareOrderDataContext，读到空购物车，抛出 CART_EMPTY。
            //B 在 catch 中再次查单，就能找到 A 已提交的订单，校验参数指纹后返回它。
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
    private OrderDataContext prepareOrderDataContext(Long userId, CreateOrderDTO dto) {
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
        validateDeliveryCoordinates(dto);
        double merchantLongitude = result.getLongitude();
        double merchantLatitude = result.getLatitude();
        double distanceKm= GeoDistanceUtils.distanceKm(merchantLongitude, merchantLatitude,
                dto.getLongitude().doubleValue(), dto.getLatitude().doubleValue());
        BigDecimal deliveryFee =
                deliveryFeeCalculator.calculateDeliveryReward(distanceKm);
        if (totalAmount.add(deliveryFee).compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "订单支付总金额超过最大结算数，请清理购物车");
        }

        OrderDataContext orderDataContext = new OrderDataContext();
        orderDataContext.setTotalAmount(totalAmount);
        orderDataContext.setMerchantId(result.getMerchantId());
        orderDataContext.setMerchantName(result.getMerchantName());
        orderDataContext.setProductMap(productMap);
        orderDataContext.setAvailableItems(availableCartItems);
        orderDataContext.setDeliveryFee(deliveryFee);
        return orderDataContext;
    }

    /**
     * 下单坐标代表固定的收货地点，是配送费的计价输入。
     * 控制器已有 Bean Validation，这里兜底拦截绕过控制器直接调用服务层的场景。
     */
    private void validateDeliveryCoordinates(CreateOrderDTO dto) {
        BigDecimal longitude = dto.getLongitude();
        BigDecimal latitude = dto.getLatitude();
        if (longitude == null || latitude == null) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "请提供收货地点经纬度");
        }
        if (longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "收货地点经纬度超出有效范围");
        }
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
        int rows = orderMapper.UpdateOrderStatusToCancel(orderId, userId, canCancelList,
                OrderStatusEnum.CANCELLED.getCode(), OrderCancelSourceEnum.USER.getCode());
        if (rows != 1) {
            // 已取消直接成功，不重复归还库存。
            Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                    .eq(Order::getId, orderId)
                    .eq(Order::getUserId, userId));
            if (order != null && OrderStatusEnum.CANCELLED.getCode().equals(order.getStatus())) {
                return;
            }
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

    /** Legacy local simulation, unavailable whenever Stripe test payments are enabled. */
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId){
        if (stripePaymentsEnabled) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,
                    "已启用 Stripe 测试支付，请使用订单支付接口或 /stripe-test.html");
        }
        if (orderMapper.completeSimulatedPayment(orderId, UserContextHolder.getUserId(),
                LocalDateTime.now().minusMinutes(30)) != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或当前状态不可支付");
        }
        notifyMerchantAfterPaymentCommit(orderId);
    }

    /** Acquires payment eligibility; the caller persists the payment record in the same transaction. */
    @Transactional(rollbackFor = Exception.class)
    public Order beginStripePayment(Long orderId, Long userId) {
        int rows = orderMapper.updateOrderStatusToPaying(orderId, userId,
                OrderStatusEnum.WAIT_PAY.getCode(), OrderStatusEnum.PAYING.getCode(),
                LocalDateTime.now().minusMinutes(30));
        // A locking read sees the CAS winner under MySQL REPEATABLE READ as well.
        Order order = orderMapper.selectOrderForUpdate(orderId);
        if (order == null || !Objects.equals(userId, order.getUserId())
                || (rows != 1 && !OrderStatusEnum.PAYING.getCode().equals(order.getStatus()))) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在、已过期或当前状态不可支付");
        }
        return order;
    }

    /** Called only for a validated succeeded snapshot, never for PaymentIntent creation alone. */
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmOrderPayment(Long orderId, Long userId) {
        int row = orderMapper.updateOrderStatusToPaid(orderId, userId,
                    OrderStatusEnum.PAYING.getCode(),
                    OrderStatusEnum.PAID.getCode(),LocalDateTime.now());
        if (row!=1) {
            Order order = orderMapper.selectOrderForUpdate(orderId);
            return order != null && Objects.equals(userId, order.getUserId())
                    && Set.of(2, 3, 5, 6, 7, 8).contains(order.getStatus());
        }
        notifyMerchantAfterPaymentCommit(orderId);
        return true;
    }

    /** Only a trusted Stripe canceled snapshot permits cancelling a PAYING order. */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelConfirmedStripePayment(Long orderId) {
        Order order = orderMapper.selectOrderForUpdate(orderId);
        if (order == null)
            return false;
        if (OrderStatusEnum.CANCELLED.getCode().equals(order.getStatus()))
            return true;

        int source = order.getCreateTime() != null
                && !order.getCreateTime().isAfter(LocalDateTime.now().minusMinutes(30))
                ? OrderCancelSourceEnum.TIMEOUT.getCode() : OrderCancelSourceEnum.PLATFORM.getCode();

        if (orderMapper.cancelStripePaymentOrder(orderId, source) != 1)
            return false;
        restoreOrderStock(orderId);
        return true;
    }

    private void notifyMerchantAfterPaymentCommit(Long orderId) {
        Long merchantId = orderMapper.selectMerchantIdByOrderId(orderId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                merchantSseService.notifyNewOrder( merchantId );
            }
        });
    }

    //NOTE:用户点击确认查收
    public void checkedOrder(Long orderId){
        settlementService.finishOrder(orderId, UserContextHolder.getUserId());
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
                expiredBefore,
                OrderCancelSourceEnum.TIMEOUT.getCode()
        );
        if (rows != 1) {
            return false;
        }
        restoreOrderStock(orderId);
        return true;
    }

    public void markMerchantOrdersSeen(Long merchantId, List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        LambdaUpdateWrapper<Order> wrapper = Wrappers.lambdaUpdate(Order.class)
                .eq(Order::getMerchantId, merchantId)
                .eq(Order::getStatus, OrderStatusEnum.PAID.getCode())
                .eq(Order::getMerchantSeen, Boolean.FALSE)
                .in(Order::getId, orderIds)
                .set(Order::getMerchantSeen, true);

        orderMapper.update(null, wrapper);
    }

    public List<Long> findMerchantUnseenOrders(Long merchantId) {
        return orderMapper.selectMerchantUnseenOrders(
                merchantId,
                OrderStatusEnum.PAID.getCode(),
                Boolean.FALSE
        );
    }
}

