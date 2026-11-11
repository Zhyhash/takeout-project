package org.example.takeout.Settlement.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.DeliveryTask.Entity.DeliveryTask;
import org.example.takeout.DeliveryTask.Enums.DeliveryTaskEnums;
import org.example.takeout.DeliveryTask.Mapper.DeliveryTaskMapper;
import org.example.takeout.Merchant.Mapper.MerchantMapper;
import org.example.takeout.Order.Entity.Order;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Rider.Mapper.RiderMapper;
import org.example.takeout.Settlement.Entity.IncomeRecord;
import org.example.takeout.Settlement.Enums.BeneficiaryTypeEnum;
import org.example.takeout.Settlement.Enums.IncomeRecordTypeEnum;
import org.example.takeout.Settlement.Mapper.IncomeRecordMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {
    private static final long ORDER_ID = 601L;
    private static final long USER_ID = 901L;
    private static final long MERCHANT_ID = 101L;
    private static final long RIDER_ID = 301L;

    @Mock private OrderMapper orderMapper;
    @Mock private DeliveryTaskMapper deliveryTaskMapper;
    @Mock private IncomeRecordMapper incomeRecordMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private RiderMapper riderMapper;
    @InjectMocks private SettlementService settlementService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, DeliveryTask.class);
    }

    @Test
    void failedStatusCompareAndSetRejectsWithoutRereadingOrWritingSettlement() {
        when(orderMapper.selectOne(any())).thenReturn(deliveredOrder());
        when(deliveryTaskMapper.selectOne(any())).thenReturn(completedTask());
        when(orderMapper.updateOrderStatusToFinished(eq(ORDER_ID), eq(USER_ID),
                eq(OrderStatusEnum.DELIVERED.getCode()), eq(OrderStatusEnum.FINISHED.getCode()), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> settlementService.finishOrder(ORDER_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在或当前状态不可确认");

        // The competing request already won the status transition. CAS=0 is terminal for this request.
        verify(orderMapper, times(1)).selectOne(any());
        verify(orderMapper).updateOrderStatusToFinished(eq(ORDER_ID), eq(USER_ID),
                eq(OrderStatusEnum.DELIVERED.getCode()), eq(OrderStatusEnum.FINISHED.getCode()), any());
        verifyNoMoreInteractions(orderMapper);
        verifyNoInteractions(incomeRecordMapper, merchantMapper, riderMapper);
    }

    @Test
    void alreadyFinishedOrderIsRejectedBeforeReadingDeliveryOrAccounts() {
        Order order = deliveredOrder();
        order.setStatus(OrderStatusEnum.FINISHED.getCode());
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> settlementService.finishOrder(ORDER_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在或当前状态不可确认");

        verify(orderMapper).selectOne(any());
        verify(orderMapper, never()).updateOrderStatusToFinished(
                any(), any(), any(), any(), any());
        verifyNoInteractions(deliveryTaskMapper, incomeRecordMapper, merchantMapper, riderMapper);
    }

    @Test
    void inconsistentOrderAmountIsRejectedBeforeAnySettlementWrite() {
        Order order = deliveredOrder();
        order.setTotalAmount(new BigDecimal("100.00"));
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(deliveryTaskMapper.selectOne(any())).thenReturn(completedTask());
        when(orderMapper.updateOrderStatusToFinished(eq(ORDER_ID), eq(USER_ID),
                eq(OrderStatusEnum.DELIVERED.getCode()), eq(OrderStatusEnum.FINISHED.getCode()), any()))
                .thenReturn(1);

        assertThatThrownBy(() -> settlementService.finishOrder(ORDER_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单金额口径不一致，无法结算");

        verifyNoInteractions(incomeRecordMapper, merchantMapper, riderMapper);
    }

    @Test
    void successfulSettlementWritesThePersistedAmountsAndIntegerEventCodes() {
        when(orderMapper.selectOne(any())).thenReturn(deliveredOrder());
        when(deliveryTaskMapper.selectOne(any())).thenReturn(completedTask());
        when(orderMapper.updateOrderStatusToFinished(eq(ORDER_ID), eq(USER_ID),
                eq(OrderStatusEnum.DELIVERED.getCode()), eq(OrderStatusEnum.FINISHED.getCode()), any()))
                .thenReturn(1);
        when(merchantMapper.creditSettledIncome(eq(MERCHANT_ID), any(BigDecimal.class))).thenReturn(1);
        when(riderMapper.creditSettledIncome(eq(RIDER_ID), any(BigDecimal.class))).thenReturn(1);

        settlementService.finishOrder(ORDER_ID, USER_ID);

        ArgumentCaptor<IncomeRecord> records = ArgumentCaptor.forClass(IncomeRecord.class);
        verify(incomeRecordMapper, times(2)).insert(records.capture());
        IncomeRecord merchantIncome = records.getAllValues().get(0);
        assertThat(merchantIncome.getBeneficiaryType()).isEqualTo(BeneficiaryTypeEnum.MERCHANT.getCode());
        assertThat(merchantIncome.getType()).isEqualTo(IncomeRecordTypeEnum.ORDER_INCOME.getCode());
        assertThat(merchantIncome.getBeneficiaryId()).isEqualTo(MERCHANT_ID);
        assertThat(merchantIncome.getAmount()).isEqualByComparingTo("92.50");

        IncomeRecord riderReward = records.getAllValues().get(1);
        assertThat(riderReward.getBeneficiaryType()).isEqualTo(BeneficiaryTypeEnum.RIDER.getCode());
        assertThat(riderReward.getType()).isEqualTo(IncomeRecordTypeEnum.DELIVERY_REWARD.getCode());
        assertThat(riderReward.getBeneficiaryId()).isEqualTo(RIDER_ID);
        assertThat(riderReward.getAmount()).isEqualByComparingTo("6.25");

        InOrder ledgerAndBalances = inOrder(incomeRecordMapper, merchantMapper, riderMapper);
        ledgerAndBalances.verify(incomeRecordMapper).insert(merchantIncome);
        ledgerAndBalances.verify(incomeRecordMapper).insert(riderReward);
        // 余额和累计收入由同一条结算 SQL 原子累加。
        ledgerAndBalances.verify(merchantMapper).creditSettledIncome(MERCHANT_ID, new BigDecimal("92.50"));
        ledgerAndBalances.verify(riderMapper).creditSettledIncome(RIDER_ID, new BigDecimal("6.25"));
    }

    private Order deliveredOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setMerchantId(MERCHANT_ID);
        order.setStatus(OrderStatusEnum.DELIVERED.getCode());
        order.setOriginalAmount(new BigDecimal("105.00"));
        order.setDiscountAmount(new BigDecimal("12.50"));
        order.setDeliveryFee(new BigDecimal("8.50"));
        order.setTotalAmount(new BigDecimal("101.00"));
        return order;
    }

    private DeliveryTask completedTask() {
        DeliveryTask task = new DeliveryTask();
        task.setId(501L);
        task.setOrderId(ORDER_ID);
        task.setRiderId(RIDER_ID);
        task.setStatus(DeliveryTaskEnums.COMPLETED.getCode());
        task.setDeliveryReward(new BigDecimal("6.25"));
        task.setAcceptedTime(LocalDateTime.of(2026, 10, 29, 12, 0));
        task.setDeliveredTime(LocalDateTime.of(2026, 10, 29, 12, 30));
        return task;
    }
}
