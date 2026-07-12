package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
import org.example.takeout.Cart.Service.cartDomainService;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.UserContextHolder;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.DTO.CreateOrderDTO;
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
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderVOBuilder orderVOBuilder;
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private OrderConvertor orderConvertor;
    @Autowired
    private cartDomainService cartDomainService;
    //NOTE:这里的数据很多，我在这里额外提醒
    //购物车 cartItems 是什么？->这是：“用户想买什么”,这是“用户的购买意图”
    //比如productId = 1，quantity = 2，但是并不可信，数据随时会变

    //Product 是什么？->这是：“系统当前真实商品信息”
    //比如：price = 18.5，stock = 10，status = ENABLE
    //最终交易：必须以 Product 为准。

    //为什么需要 productMap？->Map 只是：“为了快速查商品”。
    //因为：你后面会遍历 cartItems
    //而：每个 cartItem：都有productId
    //所以：你需要通过 productId快速找到 Product
    //所以：Map<Long, Product>本质只是：商品字典。





    @Transactional(rollbackFor = Exception.class)
    public CreateOrderVO createOrder(@NonNull CreateOrderDTO createOrderDTO) {
        Long userId = UserContextHolder.getUserId();

        // 1. 获取可用购物车（内部已校验商品/商家状态）
        List<CartItem> availableCartItems = cartDomainService.getAvailableCartItems(userId);
        if (availableCartItems == null || availableCartItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "购物车为空，无法创建订单");
        }

        // 2. 防脏数据：过滤多商家情况
        Set<Long> merchantIds = availableCartItems.stream()
                .map(CartItem::getMerchantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (merchantIds.size() != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "购物车包含多个商家的商品，请清空后重试");
        }
        Long merchantId = merchantIds.iterator().next();

        // 3. 既然 getAvailableCartItems 已经校验过商家，直接通过 MP 查出商家名称用于赋值
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商家不存在");
        }

        // 4. 批量查询商品信息（用于计算价格和组装订单项）
        List<Long> productIds = availableCartItems.stream().map(CartItem::getProductId).toList();
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        // 5. 计算金额
        BigDecimal totalAmount = orderDomainService.calculateTotalAmount(availableCartItems, productMap);

        // 6. 组装并保存订单主表
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNo(orderDomainService.createOrderNo());
        order.setMerchantId(merchantId);
        order.setMerchantName(merchant.getMerchantName());
        order.setTotalAmount(totalAmount);
        order.setOriginalAmount(totalAmount);
        order.setDiscountAmount(BigDecimal.ZERO); // TODO: 留作后续扩展

        orderConvertor.toOrder(createOrderDTO, order);
        order.setStatus(OrderStatusEnum.WAIT_PAY.getCode());

        // 使用 MP 插入订单
        orderMapper.insert(order);

        // 7. 循环处理商品：逻辑校验、内存更新、组装详情
        List<OrderItem> orderItems = new ArrayList<>();
        List<Product> productsToUpdate = new ArrayList<>();

        for (CartItem cartItem : availableCartItems) {
            Product product = productMap.get(cartItem.getProductId());
            if (product == null) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品不存在");
            }

            // 基础业务逻辑校验
            if (product.getStock() < cartItem.getQuantity()) {
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商品 [" + product.getProductName() + "] 库存不足");
            }

            // 纯业务逻辑修改：更新内存中的库存对象
            product.setStock(product.getStock() - cartItem.getQuantity());
            productsToUpdate.add(product);

            // 组装订单详情从表
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(product.getId());
            item.setProductName(product.getProductName());
            item.setProductPrice(product.getPrice());
            item.setQuantity(cartItem.getQuantity());
            item.setSubtotal(product.getPrice().multiply(new BigDecimal(cartItem.getQuantity())));
            orderItems.add(item);
        }

        // 8. 统一使用 MP 的 Db 静态工具类进行批量数据库持久化
        // 批量更新商品库存
        Db.updateBatchById(productsToUpdate);
        // 批量插入订单详情
        Db.saveBatch(orderItems);

        // 9. 清理购物车
        cartMapper.deleteBatchIds(availableCartItems.stream().map(CartItem::getId).toList());

        return orderVOBuilder.toCreateOrderVO(order);
    }

    //NOTE:查询单个id订单
    /**
     * orderId:表示订单的id
     * */
    public OrderDetailVO searchOrderDetailById(@NonNull Long orderId){
        Long userId = UserContextHolder.getUserId();
        //只查询订单，不需要状态机
        Order order = orderDomainService.getAndCheckOrder(orderId, userId, null);

        //查询item
        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, order.getId()));

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
        //检查订单id是否存在
        Order order = orderDomainService.getAndCheckOrder(orderId, userId, OrderStatusEnum.WAIT_PAY.getCode());

        //修改状态，需要二次使用数据库，似乎简化不了
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        int updateCount = orderMapper.updateById(order);
        if (updateCount != 1) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "取消订单失败，请刷新后重试");
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
        // 【修改点 2】将查出来的商品转为 Map，用于后续的存在性校验（消灭 NPE）
        Map<Long, Product> productMap = products == null ? Collections.emptyMap() :
                products.stream().collect(Collectors.toMap(Product::getId, p -> p, (k1, k2) -> k1));

        // 【修改点 3】循环进行合法性校验与原子库存回滚
        for (OrderItem item : orderItems) {
            // 校验：利用 Map 判断商品在数据库中是否还存在
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
        Order order = orderDomainService.getAndCheckOrder(orderId, userId, OrderStatusEnum.WAIT_PAY.getCode());
        //订单已经支付
        if (paying()){
            order.setStatus(OrderStatusEnum.PAID.getCode());
            order.setUpdateTime(LocalDateTime.now());
            order.setPayTime(LocalDateTime.now());
            orderMapper.updateById(order);
        }
    }

    //NOTE:用户点击确认查收
    @Transactional(rollbackFor = Exception.class)
    public void CheckedOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        Order order = orderDomainService.getAndCheckOrder(orderId, userId, OrderStatusEnum.PAID.getCode());

        order.setStatus(OrderStatusEnum.FINISHED.getCode());
        order.setUpdateTime(LocalDateTime.now());
        order.setFinishTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }
}

