package org.example.takeout.Merchant.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Enums.MerchantOrderListType;
import org.example.takeout.Merchant.Mapper.MerchantOrderConverter;
import org.example.takeout.Merchant.VO.MerchantOrderDetailVO;
import org.example.takeout.Merchant.VO.MerchantOrderListVO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderDomainService;
import org.example.takeout.Order.VO.OrderItemVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家订单查询。所有查询都按当前登录商家的 ID 过滤，避免跨店读取订单和收货信息。
 */
@Service
public class MerchantOrderQueryService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private MerchantOrderConverter merchantOrderConverter;
    @Autowired
    private OrderConvertor orderConvertor;
    @Autowired
    private OrderDomainService orderDomainService;

    public PageInfo<MerchantOrderListVO> listOrders(MerchantOrderListType type,
                                                     Integer pageNum,
                                                     Integer pageSize) {
        Long merchantId = requireMerchantId();
        List<Integer> statuses = statusesFor(type);

        PageHelper.startPage(pageNum, pageSize);
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getMerchantId, merchantId)
                .in(Order::getStatus, statuses)
                .orderByDesc(Order::getCreateTime));
        if (orders == null) {
            orders = Collections.emptyList();
        }
        PageInfo<Order> pageInfo = new PageInfo<>(orders);
        if (orders.isEmpty()) {
            return pageInfo.convert(order -> merchantOrderConverter.toMerchantOrderListVO(order));
        }

        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .in(OrderItem::getOrderId, orderIds));
        Map<Long, List<OrderItem>> itemsByOrderId = (items == null ? Collections.<OrderItem>emptyList() : items)
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        return pageInfo.convert(order -> {
            MerchantOrderListVO vo = merchantOrderConverter.toMerchantOrderListVO(order);
            vo.setProductSummary(orderDomainService.buildProductSummary(
                    itemsByOrderId.getOrDefault(order.getId(), Collections.emptyList())));
            return vo;
        });
    }

    public MerchantOrderDetailVO getOrderDetail(Long orderId) {
        Long merchantId = requireMerchantId();
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getMerchantId, merchantId));
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或不属于当前商家");
        }

        List<OrderItem> orderItems = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, orderId));
        if (orderItems == null || orderItems.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单商品明细异常，查询失败");
        }

        MerchantOrderDetailVO vo = merchantOrderConverter.toMerchantOrderDetailVO(order);
        List<OrderItemVO> itemVOs = orderItems.stream()
                .map(orderConvertor::toOrderItemVO)
                .collect(Collectors.toList());
        vo.setItems(itemVOs);
        return vo;
    }

    private Long requireMerchantId() {
        Long merchantId = MerchantContextHolder.getMerchantId();
        if (merchantId == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "商家身份无效");
        }
        return merchantId;
    }

    private List<Integer> statusesFor(MerchantOrderListType type) {
        if (type == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单列表类型不能为空");
        }
        if (type == MerchantOrderListType.PENDING) {
            return List.of(OrderStatusEnum.PAID.getCode());
        }
        return List.of(
                OrderStatusEnum.PREPARING.getCode(),
                OrderStatusEnum.READY.getCode(),
                OrderStatusEnum.DELIVERING.getCode(),
                OrderStatusEnum.DELIVERED.getCode(),
                OrderStatusEnum.FINISHED.getCode());
    }
}
