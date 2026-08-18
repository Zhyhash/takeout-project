package org.example.takeout.Merchant.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.DeliveryTask.Service.DeliveryTaskService;
import org.example.takeout.Merchant.DTO.MerchantUpdateDTO;
import org.example.takeout.Merchant.Entity.Merchant;
import org.example.takeout.Merchant.Mapper.MerchantConverter;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Record.MarkReadyResult;
import org.example.takeout.Order.Service.OrderCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MerchantFulfillmentServiceTest {

    @Mock
    private MerchantMapper merchantMapper;

    @Mock
    private MerchantConverter merchantConverter;

    @Mock
    private OrderCommandService orderCommandService;

    @Mock
    private DeliveryTaskService deliveryTaskService;

    @InjectMocks
    private MerchantService merchantService;

    @AfterEach
    void clearMerchantContext() {
        MerchantContextHolder.clear();
    }

    @Test
    void acceptOrderDelegatesToOrderCommandServiceWithCurrentMerchant() {
        MerchantContextHolder.setMerchantId(101L);

        merchantService.acceptOrder(501L);

        verify(orderCommandService).acceptOrderByMerchant(501L, 101L);
    }

    @Test
    void completePreparationCreatesDeliveryTaskAfterOrderBecomesReady() {
        Merchant merchant = currentMerchant();
        Order order = readyOrder();
        when(orderCommandService.markReadyByMerchant(501L, 101L))
                .thenReturn(new MarkReadyResult(true, order));

        merchantService.completePreparation(501L);

        verify(deliveryTaskService).createWaitingTask(order, merchant);
        verify(deliveryTaskService, never()).assertWaitingDeliveryTask(any());
    }

    @Test
    void completePreparationIsIdempotentWhenOrderWasAlreadyReady() {
        currentMerchant();
        Order order = readyOrder();
        when(orderCommandService.markReadyByMerchant(501L, 101L))
                .thenReturn(new MarkReadyResult(false, order));

        assertDoesNotThrow(() -> merchantService.completePreparation(501L));

        verify(deliveryTaskService).assertWaitingDeliveryTask(501L);
        verify(deliveryTaskService, never()).createWaitingTask(any(), any());
    }

    @Test
    void completePreparationPropagatesDeliveryTaskConsistencyFailure() {
        currentMerchant();
        when(orderCommandService.markReadyByMerchant(501L, 101L))
                .thenReturn(new MarkReadyResult(false, readyOrder()));
        BusinessException expected = new BusinessException(
                ResultCodeEnum.BUSINESS_ERROR,
                "配送任务状态不一致");
        org.mockito.Mockito.doThrow(expected)
                .when(deliveryTaskService).assertWaitingDeliveryTask(501L);

        BusinessException actual = assertThrows(
                BusinessException.class,
                () -> merchantService.completePreparation(501L));

        assertSame(expected, actual);
        verify(deliveryTaskService, never()).createWaitingTask(any(), any());
    }

    @Test
    void completePreparationRejectsMissingMerchantBeforeChangingOrder() {
        MerchantContextHolder.setMerchantId(101L);
        when(merchantMapper.selectById(101L)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> merchantService.completePreparation(501L));

        assertEquals("商户不存在", exception.getMessage());
        verify(orderCommandService, never()).markReadyByMerchant(any(), any());
        verify(deliveryTaskService, never()).createWaitingTask(any(), any());
    }

    @Test
    void completePreparationPropagatesTaskCreationFailure() {
        Merchant merchant = currentMerchant();
        Order order = readyOrder();
        when(orderCommandService.markReadyByMerchant(501L, 101L))
                .thenReturn(new MarkReadyResult(true, order));
        BusinessException expected = new BusinessException(
                ResultCodeEnum.BUSINESS_ERROR,
                "配送任务创建失败");
        org.mockito.Mockito.doThrow(expected)
                .when(deliveryTaskService).createWaitingTask(order, merchant);

        BusinessException actual = assertThrows(
                BusinessException.class,
                () -> merchantService.completePreparation(501L));

        assertSame(expected, actual);
    }

    @Test
    void updateMerchantRejectsPhoneOwnedByAnotherMerchant() {
        MerchantContextHolder.setMerchantId(101L);
        Merchant existingMerchant = new Merchant();
        existingMerchant.setId(101L);
        existingMerchant.setPhone("13800138000");
        when(merchantMapper.selectOne(any())).thenReturn(existingMerchant);
        when(merchantMapper.selectCount(any())).thenReturn(1L);

        MerchantUpdateDTO dto = new MerchantUpdateDTO();
        dto.setPhone("13900139000");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> merchantService.updateMerchant(dto));

        assertEquals("手机号已被其他商家使用", exception.getMessage());
        verify(merchantMapper).selectCount(any());
        verify(merchantMapper, never()).updateById(any(Merchant.class));
    }

    private Merchant currentMerchant() {
        MerchantContextHolder.setMerchantId(101L);
        Merchant merchant = new Merchant();
        merchant.setId(101L);
        when(merchantMapper.selectById(101L)).thenReturn(merchant);
        return merchant;
    }

    private Order readyOrder() {
        Order order = new Order();
        order.setId(501L);
        order.setMerchantId(101L);
        return order;
    }
}
