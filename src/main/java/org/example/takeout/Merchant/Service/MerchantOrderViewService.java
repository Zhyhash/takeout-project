package org.example.takeout.Merchant.Service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Enums.MerchantOrderListType;
import org.example.takeout.Merchant.Mapper.MerchantOrderConverter;
import org.example.takeout.Merchant.VO.MerchantOrderDetailVO;
import org.example.takeout.Merchant.VO.MerchantOrderListVO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.Support.OrderProductSummaryBuilder;
import org.example.takeout.Order.VO.OrderItemVO;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家订单查询。所有查询都按当前登录商家的 ID 过滤，避免跨店读取订单和收货信息。
 */
@Service
@RequiredArgsConstructor
public class MerchantOrderViewService {

    private final MerchantOrderConverter merchantOrderConverter;
    private final OrderItemService orderItemService;
    private final OrderService orderService;
    private final OrderProductSummaryBuilder orderProductSummaryBuilder;

    public PageInfo<MerchantOrderListVO> listOrders(MerchantOrderListType type,
                                                     Integer pageNum,
                                                     Integer pageSize) {
        Long merchantId = requireMerchantId();
        if (type == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单列表类型不能为空");
        }

        PageHelper.startPage(pageNum, pageSize);
        List<Order> orders = switch (type) {
            case PENDING -> orderService.findMerchantPendingOrders(merchantId);
            case PROCESSING -> orderService.findMerchantProcessingOrders(merchantId);
            case HISTORY -> orderService.findMerchantHistoryOrders(merchantId);
        };
        if (orders == null) {
            orders = Collections.emptyList();
        }
        PageInfo<Order> pageInfo = new PageInfo<>(orders);
        if (orders.isEmpty()) {
            return pageInfo.convert(merchantOrderConverter::toMerchantOrderListVO);
        }

        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<OrderItem> items = orderItemService.findMerchantOrderItemsByOrderIds(orderIds);
        Map<Long, List<OrderItem>> itemsByOrderId = (items == null ? Collections.<OrderItem>emptyList() : items)
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        return pageInfo.convert(order -> {
            MerchantOrderListVO vo = merchantOrderConverter.toMerchantOrderListVO(order);
            vo.setProductSummary(orderProductSummaryBuilder.buildProductSummary(
                    itemsByOrderId.getOrDefault(order.getId(), Collections.emptyList())));
            return vo;
        });
    }

    public MerchantOrderDetailVO getOrderDetail(Long orderId) {
        Long merchantId = requireMerchantId();
        Order order = orderService.findMerchantOrderById(orderId, merchantId);
        if (order == null) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单不存在或不属于当前商家");
        }

        List<OrderItemVO> itemVOs = orderItemService.findMerchantOrderItemVOsByOrderIds(List.of(orderId));
        if (itemVOs.isEmpty()) {
            throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "订单商品明细异常，查询失败");
        }

        MerchantOrderDetailVO vo = merchantOrderConverter.toMerchantOrderDetailVO(order);
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

}
