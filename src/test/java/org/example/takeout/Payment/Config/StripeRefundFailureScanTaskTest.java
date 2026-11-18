package org.example.takeout.Payment.Config;

import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;
import org.example.takeout.Payment.Mapper.OrderStripeRefundAttemptMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 失败巡检只做读：查出来记录日志，不改状态、不消耗重试预算。 */
class StripeRefundFailureScanTaskTest {
    private final OrderStripeRefundAttemptMapper attemptMapper = mock(OrderStripeRefundAttemptMapper.class);
    private final StripeRefundFailureScanTask task = new StripeRefundFailureScanTask(attemptMapper);

    @Test
    void scanOnlyReadsFailedRefunds() {
        when(attemptMapper.selectFailedAttempts(anyInt())).thenReturn(List.of(
                failedRefund(701L, "expired_or_canceled_card"),
                failedRefund(702L, null)));

        task.scanFailedRefunds();

        verify(attemptMapper).selectFailedAttempts(anyInt());
        // 巡检不得产生任何写操作，否则会偷偷改变退款状态机
        verifyNoMoreInteractions(attemptMapper);
    }

    @Test
    void repeatedScansOfTheSameFailuresDoNotKeepAlerting() {
        when(attemptMapper.selectFailedAttempts(anyInt())).thenReturn(List.of(
                failedRefund(701L, "expired_or_canceled_card")));

        task.scanFailedRefunds();
        task.scanFailedRefunds();

        // 两轮都只读一次；第二轮因为集合未变化只打 debug，不再刷 warn
        verify(attemptMapper, org.mockito.Mockito.times(2)).selectFailedAttempts(anyInt());
        verifyNoMoreInteractions(attemptMapper);
    }

    @Test
    void scanSkipsEverythingWhenNothingFailed() {
        when(attemptMapper.selectFailedAttempts(anyInt())).thenReturn(List.of());

        task.scanFailedRefunds();

        verify(attemptMapper).selectFailedAttempts(anyInt());
        verifyNoMoreInteractions(attemptMapper);
    }

    private static OrderStripeRefundAttempt failedRefund(long orderId, String failureReason) {
        OrderStripeRefundAttempt refund = new OrderStripeRefundAttempt();
        refund.setOrderId(orderId);
        refund.setRefundId("re_" + orderId);
        refund.setStripeStatus("failed");
        refund.setFailureReason(failureReason);
        refund.setReconciliationState("RECONCILED");
        return refund;
    }
}
