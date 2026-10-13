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
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Order.Service.OrderService;
import org.example.takeout.Order.Support.OrderProductSummaryBuilder;
import org.example.takeout.Order.VO.OrderItemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantOrderViewServiceTest {

    @Mock
    private MerchantOrderConverter merchantOrderConverter;
    @Mock
    private OrderItemService orderItemService;
    @Mock
    private OrderService orderService;
    @Spy
    private OrderProductSummaryBuilder orderProductSummaryBuilder = new OrderProductSummaryBuilder();

    @InjectMocks
    private MerchantOrderViewService merchantOrderViewService;

    @AfterEach
    void clearContext() {
        MerchantContextHolder.clear();
        PageHelper.clearPage();
    }

    @Test
    void pendingListDelegatesCurrentMerchantQueryAndBuildsProductSummary() {
        MerchantContextHolder.setMerchantId(11L);
        Order order = new Order();
        order.setId(101L);
        when(orderService.findMerchantPendingOrders(11L)).thenReturn(List.of(order));
        when(orderItemService.findMerchantOrderItemsByOrderIds(List.of(101L)))
                .thenReturn(List.of(orderItem(101L)));
        when(merchantOrderConverter.toMerchantOrderListVO(order)).thenReturn(new MerchantOrderListVO());

        PageInfo<MerchantOrderListVO> result = merchantOrderViewService.listOrders(
                MerchantOrderListType.PENDING, 1, 10);

        assertEquals(1, result.getList().size());
        assertEquals("宫保鸡丁", result.getList().get(0).getProductSummary());
        verify(orderService).findMerchantPendingOrders(11L);
    }

    @Test
    void processingListUsesMerchantProcessingQuery() {
        MerchantContextHolder.setMerchantId(11L);
        when(orderService.findMerchantProcessingOrders(11L)).thenReturn(List.of());

        merchantOrderViewService.listOrders(MerchantOrderListType.PROCESSING, 1, 10);

        verify(orderService).findMerchantProcessingOrders(11L);
    }

    @Test
    void historyListUsesMerchantHistoryQueryWithoutLoadingItemsForEmptyPage() {
        MerchantContextHolder.setMerchantId(11L);
        when(orderService.findMerchantHistoryOrders(11L)).thenReturn(List.of());

        PageInfo<MerchantOrderListVO> result = merchantOrderViewService.listOrders(
                MerchantOrderListType.HISTORY, 1, 10);

        assertEquals(0, result.getList().size());
        verify(orderService).findMerchantHistoryOrders(11L);
        verifyNoInteractions(orderItemService, merchantOrderConverter);
    }

    @Test
    void orderQueriesRejectMissingMerchantBeforeAccessingOrderModule() {
        assertThrows(BusinessException.class, () -> merchantOrderViewService.listOrders(
                MerchantOrderListType.PENDING, 1, 10));
        assertThrows(BusinessException.class, () -> merchantOrderViewService.getOrderDetail(101L));
        verifyNoInteractions(orderService, orderItemService, merchantOrderConverter);
    }

    @Test
    void listRejectsMissingTypeBeforeAccessingOrderModule() {
        MerchantContextHolder.setMerchantId(11L);

        assertThrows(BusinessException.class, () -> merchantOrderViewService.listOrders(null, 1, 10));

        verifyNoInteractions(orderService, orderItemService, merchantOrderConverter);
    }

    @Test
    void orderDetailIncludesItemsOnlyForCurrentMerchant() {
        MerchantContextHolder.setMerchantId(11L);
        Order order = new Order();
        order.setId(101L);
        order.setTotalAmount(BigDecimal.TEN);
        MerchantOrderDetailVO detailVO = new MerchantOrderDetailVO();
        OrderItemVO itemVO = new OrderItemVO();
        itemVO.setProductName("宫保鸡丁");

        when(orderService.findMerchantOrderById(101L, 11L)).thenReturn(order);
        when(orderItemService.findMerchantOrderItemVOsByOrderIds(List.of(101L))).thenReturn(List.of(itemVO));
        when(merchantOrderConverter.toMerchantOrderDetailVO(order)).thenReturn(detailVO);

        MerchantOrderDetailVO result = merchantOrderViewService.getOrderDetail(101L);

        assertEquals(1, result.getItems().size());
        assertEquals("宫保鸡丁", result.getItems().get(0).getProductName());
    }

    @Test
    void orderDetailRejectsOrderOutsideCurrentMerchant() {
        MerchantContextHolder.setMerchantId(11L);
        when(orderService.findMerchantOrderById(101L, 11L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> merchantOrderViewService.getOrderDetail(101L));
        verifyNoInteractions(orderItemService, merchantOrderConverter);
    }

    @Test
    void orderDetailRejectsMissingOrderItemsBeforeConvertingDetail() {
        MerchantContextHolder.setMerchantId(11L);
        Order order = new Order();
        order.setId(101L);
        when(orderService.findMerchantOrderById(101L, 11L)).thenReturn(order);
        when(orderItemService.findMerchantOrderItemVOsByOrderIds(List.of(101L))).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> merchantOrderViewService.getOrderDetail(101L));

        assertEquals("订单商品明细异常，查询失败", exception.getMessage());
        verifyNoInteractions(merchantOrderConverter);
    }

    private static OrderItem orderItem(Long orderId) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductName("宫保鸡丁");
        item.setQuantity(1);
        return item;
    }
}
