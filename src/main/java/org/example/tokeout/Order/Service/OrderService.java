package org.example.tokeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.example.tokeout.Cart.Entity.CartItem;
import org.example.tokeout.Cart.Mapper.CartMapper;
import org.example.tokeout.Common.Exception.BusinessException;
import org.example.tokeout.Common.Exception.CartItemInvalidException;
import org.example.tokeout.Common.Utils.Context.UserContextHolder;
import org.example.tokeout.Order.DTO.CreateOrderDTO;
import org.example.tokeout.Order.Entity.Order;
import org.example.tokeout.Order.Entity.OrderItem;
import org.example.tokeout.Order.Enums.OrderStatusEnum;
import org.example.tokeout.Order.Mapper.OrderItemMapper;
import org.example.tokeout.Order.Mapper.OrderMapper;
import org.example.tokeout.Order.VO.*;
import org.example.tokeout.Product.Entity.Product;
import org.example.tokeout.Product.Mapper.ProductMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
//TODO:记得等状态稳定的时候抽方法出来
public class OrderService {
    @Autowired
    private domain domain;
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private builder builder;
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



    //NOTE:直接抽取判断购物车是否合法方法出来
    /**
     * 用户的购物车项目
     */
    //TODO:这里有一点过度stream化了，记得改
    private Map<Long,Product> getAndCheckProducts(List<CartItem> cartItems){
        if (cartItems == null || cartItems.isEmpty())
            throw new BusinessException("购物车是空的");
        List<Long> productIds = cartItems.stream().map(CartItem::getProductId).toList();
        List<Product> products = productMapper.selectBatchIds(productIds);
        if (products==null || products.isEmpty() || products.size()< productIds.size()){
            // 找出数据库里实际存在的 ID 集合

            Set<Long> existIds = products == null ? Collections.emptySet() :
                    products.stream().filter(product -> product.
                            getStatus().equals(1)).map(Product::getId).
                            collect(Collectors.toSet());
            // 差集：找出哪些 ID 已经在数据库里被删除了
            List<Long> invalidIds = productIds.stream()
                    .filter(id -> !existIds.contains(id))
                    .toList();
            throw new CartItemInvalidException("部分商品已下架或失效，请刷新页面", invalidIds);
        }
        //productMap它的 Key 是商品 ID，Value 是商品详情对象。
        return products.stream()
                .collect(Collectors.toMap(
                        Product::getId,
                        product -> product
                ));
    }
    //NOTE:计算金额方法
    private BigDecimal calculateTotalAmount(@NonNull List<CartItem> cartItems, Map<Long, Product> productMap) {
        return cartItems.stream()
                .map(item -> productMap.
                        get(item.getProductId()).getPrice().
                        multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateOrderVO createOrder(@NonNull CreateOrderDTO createOrderDTO){
        Long userId = UserContextHolder.getUserId();
        List<CartItem> cartItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId));
        //拿到map快速查询
        Map<Long, Product> productMap = getAndCheckProducts(cartItems);
        //计算金额
        BigDecimal totalAmount = calculateTotalAmount(cartItems, productMap);

        Order order = new Order();
        //order赋值
        //TODO：商家板块需要到时候加入
        order.setUserId(userId);
        order.setOrderNo(domain.createOrderNo());
        order.setTotalAmount(totalAmount);
        BeanUtils.copyProperties(createOrderDTO, order);

        order.setStatus(OrderStatusEnum.WAIT_PAY.getCode());//未支付
        orderMapper.insert(order);

        List<OrderItem> orderItems = cartItems.stream().map(cartItem -> {
            Product product = productMap.get(cartItem.getProductId());
            // 顺便在这里拦截下库存
            //TODO:记得并发问题
            if (product.getStock() < cartItem.getQuantity())
                throw new BusinessException("库存不足");

            //扣库存
            product.setStock(product.getStock() - cartItem.getQuantity());

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(product.getId());
            item.setProductName(product.getProductName());
            item.setProductPrice(product.getPrice());
            item.setQuantity(cartItem.getQuantity());
            item.setSubtotal(product.getPrice().multiply(new BigDecimal(cartItem.getQuantity())));
            return item;
        }).toList();
        //插入数据
        orderItems.forEach(orderItemMapper::insert);

        //删除购物车
        cartMapper.deleteBatchIds(cartItems.stream().map(CartItem::getId).toList());

        //返回值
        return builder.toCreateOrderVO(order);
    }

    //NOTE:查询单个id订单
    /**
     * orderId:表示订单的id
     * */
    public OrderDetailVO searchOrderDetailById(@NonNull Long orderId){
        Long userId = UserContextHolder.getUserId();
        //只查询订单，不需要状态机
        Order order = domain.getAndCheckOrder(orderId, userId, null);

        OrderDetailVO orderDetailVO = new OrderDetailVO();
        BeanUtils.copyProperties(order, orderDetailVO);

        //查询item
        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, order.getId()));

        //返回组装好的详情对象
        return builder.toOrderDetailVO(order, orderItems);
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
        //NOTE:这一步是需要注意的捏，实际上orders已经不是List了哦
        // 数据库查出来的 orders 实际上长这样：
//        orders = [
//            data: [Order1, Order2, ..., Order10], // 当前页的10条数据
//            total: 100,                           // 默默记录的总条数是 100！
//            pages: 10                             // 总页数是 10！
//        ]+
        List<OrderItem> allItems = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery()
                        .in(OrderItem::getOrderId, orderIds)   // 使用 in，不是 eq
        );

        Map<Long, List<OrderItem>> itemsMap = allItems.stream().
                collect(Collectors.groupingBy(OrderItem::getOrderId));

        PageInfo<Order> pageInfo = new PageInfo<>(orders);
        // 原位置改成：
        List<OrderVO> orderVOs = orders.stream()
                .map(order -> builder.toOrderVO(order, itemsMap))
                .toList();

        /// 逻辑为：先从pageInfo里面拿到总数据（个数等），然后用orderVOs替换
        PageInfo<OrderVO> voPageInfo = new PageInfo<>();
        // 使用 Spring 的工具类，把 total, pageNum, pages 等分页参数全部拷过去
        // 将带有完整分页元数据（total, pages等）的 orderPageInfo 拷贝给 voPageInfo
        BeanUtils.copyProperties(pageInfo, voPageInfo);
        // 因为上面拷贝时 list 里的数据类型不对（还是 Order），所以要用转换好的 VO 列表把它覆盖掉
        voPageInfo.setList(orderVOs);
        return voPageInfo;
    }
    //NOTE:取消订单，统一返回result
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        //检查订单id是否存在
        Order order = domain.getAndCheckOrder(orderId, userId, OrderStatusEnum.WAIT_PAY.getCode());

        //修改状态，需要二次使用数据库，似乎简化不了
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        orderMapper.updateById(order);

        //取消了之后需要将库存还回去
        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery().
                eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : orderItems) {
            Product product =
                    productMapper.selectById(item.getProductId());
            product.setStock(
                    product.getStock() + item.getQuantity()
            );
            productMapper.updateById(product);
        }
    }

    //NOTE:模拟支付
    private boolean paying(){return true;}
    //NOTE:单个订单支付
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        Order order = domain.getAndCheckOrder(orderId, userId, OrderStatusEnum.WAIT_PAY.getCode());
        //订单已经支付
        if (paying()){
            order.setStatus(OrderStatusEnum.PAID.getCode());
            order.setUpdateTime(LocalDateTime.now());
            order.setPayTime(LocalDateTime.now());
            orderMapper.updateById(order);
        }
    }


    //NOTE:用户点击确认查收
    public void CheckedOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        Order order = domain.getAndCheckOrder(orderId, userId, OrderStatusEnum.WAIT_PAY.getCode());

        order.setStatus(OrderStatusEnum.FINISHED.getCode());
        order.setUpdateTime(LocalDateTime.now());
        order.setFinishTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }
}

