package org.example.takeout.Merchant.Service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Merchant.Enums.MerchantOrderListType;
import org.example.takeout.Merchant.Mapper.MerchantOrderConverter;
import org.example.takeout.Merchant.VO.MerchantOrderDetailVO;
import org.example.takeout.Merchant.VO.MerchantOrderListVO;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Entity.OrderItem;
import org.example.takeout.Order.Mapper.OrderConvertor;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderDomainService;
import org.example.takeout.Order.VO.OrderItemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantOrderQueryServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private MerchantOrderConverter merchantOrderConverter;
    @Mock
    private OrderConvertor orderConvertor;
    @Mock
    private OrderDomainService orderDomainService;

    @InjectMocks
    private MerchantOrderQueryService merchantOrderQueryService;

    @AfterEach
    void clearContext() {
        MerchantContextHolder.clear();
        PageHelper.clearPage();
    }

    @Test
    void pendingListIsRestrictedToCurrentMerchantAndPaidOrders() {
        MerchantContextHolder.setMerchantId(11L);
        Order order = new Order();
        order.setId(101L);
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(101L)));
        when(merchantOrderConverter.toMerchantOrderListVO(order)).thenReturn(new MerchantOrderListVO());
        when(orderDomainService.buildProductSummary(any())).thenReturn("宫保鸡丁");

        PageInfo<MerchantOrderListVO> result = merchantOrderQueryService.listOrders(
                MerchantOrderListType.PENDING, 1, 10);

        assertEquals(1, result.getList().size());
        assertEquals("宫保鸡丁", result.getList().get(0).getProductSummary());
        verify(orderMapper).selectList(any());
    }

    @Test
    void acceptedListCoversAllPostAcceptanceFulfillmentStatuses() {
        MerchantContextHolder.setMerchantId(11L);
        when(orderMapper.selectList(any())).thenReturn(List.of());

        merchantOrderQueryService.listOrders(MerchantOrderListType.ACCEPTED, 1, 10);

        verify(orderMapper).selectList(any());
    }

    @Test
    void orderDetailIncludesItemsOnlyForCurrentMerchant() {
        MerchantContextHolder.setMerchantId(11L);
        Order order = new Order();
        order.setId(101L);
        order.setTotalAmount(BigDecimal.TEN);
        OrderItem item = orderItem(101L);
        MerchantOrderDetailVO detailVO = new MerchantOrderDetailVO();
        OrderItemVO itemVO = new OrderItemVO();
        itemVO.setProductName("宫保鸡丁");

        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));
        when(merchantOrderConverter.toMerchantOrderDetailVO(order)).thenReturn(detailVO);
        when(orderConvertor.toOrderItemVO(item)).thenReturn(itemVO);

        MerchantOrderDetailVO result = merchantOrderQueryService.getOrderDetail(101L);

        assertEquals(1, result.getItems().size());
        assertEquals("宫保鸡丁", result.getItems().get(0).getProductName());
    }

    @Test
    void orderDetailRejectsOrderOutsideCurrentMerchant() {
        MerchantContextHolder.setMerchantId(11L);
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> merchantOrderQueryService.getOrderDetail(101L));
    }

    private static OrderItem orderItem(Long orderId) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductName("宫保鸡丁");
        item.setQuantity(1);
        return item;
    }
}
