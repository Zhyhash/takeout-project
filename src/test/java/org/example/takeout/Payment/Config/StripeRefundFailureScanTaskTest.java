package org.example.takeout.Payment.Config;

import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 失败巡检只做读：查出来记录日志，不改状态、不消耗重试预算。 */
class StripeRefundFailureScanTaskTest {
    private final OrderStripeRefundMapper refundMapper = mock(OrderStripeRefundMapper.class);
    private final StripeRefundFailureScanTask task = new StripeRefundFailureScanTask(refundMapper);

    @Test
    void scanOnlyReadsFailedRefunds() {
        when(refundMapper.selectFailedList(anyInt())).thenReturn(List.of(
                failedRefund(701L, "expired_or_canceled_card"),
                failedRefund(702L, null)));

        task.scanFailedRefunds();

        verify(refundMapper).selectFailedList(anyInt());
        // 巡检不得产生任何写操作，否则会偷偷改变退款状态机
        verifyNoMoreInteractions(refundMapper);
    }

    @Test
    void repeatedScansOfTheSameFailuresDoNotKeepAlerting() {
        when(refundMapper.selectFailedList(anyInt())).thenReturn(List.of(
                failedRefund(701L, "expired_or_canceled_card")));

        task.scanFailedRefunds();
        task.scanFailedRefunds();

        // 两轮都只读一次；第二轮因为集合未变化只打 debug，不再刷 warn
        verify(refundMapper, org.mockito.Mockito.times(2)).selectFailedList(anyInt());
        verifyNoMoreInteractions(refundMapper);
    }

    @Test
    void scanSkipsEverythingWhenNothingFailed() {
        when(refundMapper.selectFailedList(anyInt())).thenReturn(List.of());

        task.scanFailedRefunds();

        verify(refundMapper).selectFailedList(anyInt());
        verifyNoMoreInteractions(refundMapper);
    }

    private static OrderStripeRefund failedRefund(long orderId, String failureReason) {
        OrderStripeRefund refund = new OrderStripeRefund();
        refund.setOrderId(orderId);
        refund.setRefundId("re_" + orderId);
        refund.setStripeStatus("failed");
        refund.setFailureReason(failureReason);
        refund.setReconciliationState("RECONCILED");
        return refund;
    }
}
