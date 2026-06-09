package org.example.takeout.Order.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.example.takeout.Cart.Entity.CartItem;
import org.example.takeout.Cart.Mapper.CartMapper;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public CreateOrderVO createOrder(@NonNull CreateOrderDTO createOrderDTO){
        Long userId = UserContextHolder.getUserId();
        List<CartItem> cartItems = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId));
        if (cartItems == null || cartItems.isEmpty()){
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"购物车为空，五创建订单");
        }
        Long merchantId = cartItems.get(0).getMerchantId();
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"商家不存在");
        }
        //拿到map快速查询
        Map<Long, Product> productMap = orderDomainService.getAndCheckProducts(cartItems);
        //计算金额
        BigDecimal totalAmount = orderDomainService.calculateTotalAmount(cartItems, productMap);

        Order order = new Order();
        //order赋值

        order.setUserId(userId);
        order.setOrderNo(orderDomainService.createOrderNo());
        order.setMerchantId(merchantId);
        order.setMerchantName(merchant.getMerchantName());
        order.setTotalAmount(totalAmount);
        //TODO:为了扩展性，这里需要修改的，不能让他直接一个0在这里，shit，怎么感觉又要修数据库了
        order.setDiscountAmount(BigDecimal.ZERO);


        orderConvertor.toOrder(createOrderDTO, order);

        order.setStatus(OrderStatusEnum.WAIT_PAY.getCode());//未支付
        orderMapper.insert(order);

        List<OrderItem> orderItems = cartItems.stream().map(cartItem -> {
            Product product = productMap.get(cartItem.getProductId());
            // 顺便在这里拦截下库存
            //TODO:记得并发问题
            if (product.getStock() < cartItem.getQuantity())
                throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"库存不足");

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

        order.setOriginalAmount(totalAmount);
        order.setDiscountAmount(BigDecimal.ZERO);

        //返回值
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
        //NOTE:这一步是需要注意的捏，实际上orders已经不是List了哦
        // 数据库查出来的 orders 实际上长这样：
//        orders = [
//            data: [Order1, Order2, ..., Order10], // 当前页的10条数据
//            total: 100,                           // 默默记录的总条数是 100！
//            pages: 10                             // 总页数是 10！
//        ]
        List<OrderItem> allItems = orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery()
                        .in(OrderItem::getOrderId, orderIds)   // 使用 in，不是 eq
        );

        Map<Long, List<OrderItem>> itemsMap = allItems.stream().
                collect(Collectors.groupingBy(OrderItem::getOrderId));

        PageInfo<Order> pageInfo = new PageInfo<>(orders);
        // 原位置改成：
        List<OrderVO> orderVOs = orders.stream()
                .map(order -> orderVOBuilder.toOrderVO(order, itemsMap))
                .toList();
        return PageInfo.of(orderVOs, pageInfo.getNavigatePages());
//        /// 逻辑为：先从pageInfo里面拿到总数据（个数等），然后用orderVOs替换
//        PageInfo<OrderVO> voPageInfo = new PageInfo<>();
//        // 使用 Spring 的工具类，把 total, pageNum, pages 等分页参数全部拷过去
//        // 将带有完整分页元数据（total, pages等）的 orderPageInfo 拷贝给 voPageInfo
//        BeanUtils.copyProperties(pageInfo, voPageInfo);
//        // 因为上面拷贝时 list 里的数据类型不对（还是 Order），所以要用转换好的 VO 列表把它覆盖掉
//        voPageInfo.setList(orderVOs);
//        return voPageInfo;
    }
    //NOTE:取消订单，统一返回result
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        //检查订单id是否存在
        Order order = orderDomainService.getAndCheckOrder(orderId, userId, OrderStatusEnum.WAIT_PAY.getCode());

        //修改状态，需要二次使用数据库，似乎简化不了
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        orderMapper.updateById(order);

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


            //TODO：这里是AI写的，v2并发回来看一下
            // 【核心修改点 4】防御高并发冲突！
            // 绝对不能在 Java 中计算 product.setStock(stock + quantity) 再 updateById！
            // 必须使用 MyBatis-Plus 的 LambdaUpdateChainWrapper 或者是手写 SQL 实现原子自增：
            boolean updateSuccess = new LambdaUpdateChainWrapper<>(productMapper)
                    .eq(Product::getId, item.getProductId())
                    .setSql("stock = stock + {0}" + item.getQuantity()) // SQL 层面的原子操作，自带行锁
                    .update();

            if (!updateSuccess) {
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
    public void CheckedOrder(Long orderId){
        Long userId = UserContextHolder.getUserId();
        Order order = orderDomainService.getAndCheckOrder(orderId, userId, OrderStatusEnum.PAID.getCode());

        order.setStatus(OrderStatusEnum.FINISHED.getCode());
        order.setUpdateTime(LocalDateTime.now());
        order.setFinishTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }
}

