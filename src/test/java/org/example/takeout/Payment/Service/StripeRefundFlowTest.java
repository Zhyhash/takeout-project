package org.example.takeout.Payment.Service;

import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Payment.Config.StripeRefundRecoveryTask;
import org.example.takeout.Payment.Config.StripeRefundRetryPolicy;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.RefundStatus;
import org.example.takeout.Payment.Domain.RefundSyncResult;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Gateway.StripeWebhookEvent;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundAttemptMapper;
import org.example.takeout.Payment.VO.RefundExecution;
import org.example.takeout.Payment.Mapper.StripeRefundEventMapper;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商家拒单退款的三轮验收测试。
 *
 * <p>用 H2(MODE=MySQL) 承载真实 MyBatis SQL 与真实事务边界，只有 {@link StripeRefundGateway}
 * 是假实现：它代替 Stripe HTTP 调用，因此「远端已退款」「远端已创建但本地写失败」这类
 * 只能靠打桩制造的时序都可以确定性复现，不会打到真实 Stripe 账户。</p>
 *
 * <p>三轮分别验证：</p>
 * <ol>
 *   <li>正常退款：订单落 {@code REFUNDED}、退款记录 {@code succeeded/RECONCILED}、库存只归还一次；</li>
 *   <li>重复请求与恢复：重复拒单、重复同步同一份 succeeded 快照不会再次退款或归还库存；
 *       本地 {@code PENDING} 且已绑定 {@code refundId} 时靠 {@code retrieveRefund}
 *       推进，而不是再下一笔；</li>
 *   <li>故障恢复：远端创建成功但本地 {@code syncRefundStatus} 失败时，事务回滚后本地仍是
 *       {@code PENDING}，恢复任务复用同一幂等键重新同步，不会产生第二笔退款。</li>
 * </ol>
 *
 * <p>真实 Stripe 测试环境的核对见 {@code StripeRefundSandboxLiveTest}。</p>
 */
@SpringJUnitConfig(StripeRefundFlowTest.TestConfig.class)
@TestPropertySource(properties = {
        "payment.stripe.enabled=true",
        "payment.stripe.refund.auto-retry-failure-reasons=retryable_test_reason"
})
class StripeRefundFlowTest {

    private static final long ORDER_ID = 701L;
    private static final long USER_ID = 301L;
    private static final long MERCHANT_ID = 401L;
    private static final long PRODUCT_ID = 902L;
    private static final long ORDER_ITEM_ID = 1L;

    private static final String PAYMENT_INTENT_ID = "pi_refund_flow_701";
    private static final String REFUND_ID = "re_refund_flow_701";
    private static final String NEXT_REFUND_ID = "re_refund_flow_701_next";
    private static final long AMOUNT_MINOR = 12_345L;
    private static final String CURRENCY = "cny";
    private static final String RETRYABLE_FAILURE_REASON = "retryable_test_reason";

    /** 下单扣减后的库存，拒单应该把它补回 {@link #ITEM_QUANTITY} 件。 */
    private static final int STOCK_AFTER_ORDER = 7;
    private static final int ITEM_QUANTITY = 3;
    private static final int STOCK_AFTER_REFUND = STOCK_AFTER_ORDER + ITEM_QUANTITY;

    @Autowired private OrderCommandService orderCommands;
    @Autowired private StripeRefundService stripeRefundService;
    @Autowired private StripeRefundTransactionService refundTransactions;
    @Autowired private StripeRefundRecoveryTask refundRecoveryTask;
    @Autowired private StripeRefundGateway refundGateway;
    @Autowired private OrderStripeRefundMapper refundMapper;
    @Autowired private OrderStripeRefundAttemptMapper refundAttemptMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        reset(refundGateway);
        recreateSchema();
        insertPaidOrderWithSucceededPayment();
    }

    // ------------------------------------------------------------------
    // 第一轮：正常退款
    // ------------------------------------------------------------------

    /**
     * 商家拒单 → Stripe 退款成功 → 订单 REFUNDED、退款记录 succeeded/RECONCILED、库存只归还一次。
     * <p>Stripe 后台是否真的存在退款对象，在假网关里等价于：创建时用的
     * {@code PaymentIntent + 金额 + 币种 + 幂等键} 正确，且落库的就是 Stripe 返回的退款单号。</p>
     */
    @Test
    void merchantRejectionRefundsOrderAndReturnsInventoryExactlyOnce() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("succeeded"));

        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());

        OrderStripeRefund refund = refundRecord();
        OrderStripeRefundAttempt attempt = attemptRecord();
        assertNotNull(refund);
        assertEquals(RefundStatus.SUCCEEDED.code(), refund.getStatus());
        assertEquals(AMOUNT_MINOR, refund.getAmountMinor());
        assertEquals(CURRENCY, refund.getCurrency());
        assertEquals(REFUND_ID, attempt.getRefundId());
        assertEquals("succeeded", attempt.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), attempt.getReconciliationState());
        assertTrue(attempt.getIdempotencyKey().startsWith("refund_attempt_"));

        // 整条链路只向 Stripe 发过一次「创建退款」，没有走回查兜底
        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(attempt.getIdempotencyKey()));
        verify(refundGateway, never()).retrieveRefund(anyString());
    }

    /**
     * 第一轮的补充：用落库的 {@code refundId} 回查 Stripe，能取到同一笔退款对象。
     * <p>这一步对应人工在 Stripe 测试后台（或 {@code stripe refunds retrieve}）核对退款对象存在，
     * 只是把「人眼看后台」换成了「同样的 retrieve 接口断言」。</p>
     */
    @Test
    void persistedRefundObjectCanBeRetrievedFromStripe() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("succeeded"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        String refundId = attemptRecord().getRefundId();
        assertNotNull(refundId);

        when(refundGateway.retrieveRefund(refundId)).thenReturn(snapshot("succeeded"));
        StripeRefundSnapshot remote = refundGateway.retrieveRefund(refundId);

        assertEquals(refundId, remote.refundId());
        assertEquals(PAYMENT_INTENT_ID, remote.paymentIntentId());
        assertEquals(AMOUNT_MINOR, remote.amountMinor());
        assertEquals(CURRENCY, remote.currency());
        assertEquals("succeeded", remote.status());
    }

    // ------------------------------------------------------------------
    // 第二轮：重复请求与恢复
    // ------------------------------------------------------------------

    /** 重复拒单：退款已完成时直接返回，不会二次退款，也不会二次归还库存。 */
    @Test
    void repeatedMerchantRejectionDoesNotRefundOrReturnInventoryTwice() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("succeeded"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        assertEquals(STOCK_AFTER_REFUND, stock());

        assertDoesNotThrow(() -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));
        assertDoesNotThrow(() -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());
        assertEquals(REFUND_ID, attemptRecord().getRefundId());
        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(attemptRecord().getIdempotencyKey()));
        verify(refundGateway, never()).retrieveRefund(anyString());
    }

    /**
     * 重复同步同一份 succeeded 快照：状态已一致且已绑定退款单号，直接幂等返回。
     * <p>如果这里没有幂等短路，第二次 {@code updateOrderStatusToRefunded} 会因为订单已是
     * {@code REFUNDED}（不再是 {@code REFUNDING}）而影响 0 行并抛异常。</p>
     */
    @Test
    void replayingTheSameSucceededSnapshotKeepsTheTerminalState() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("succeeded"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        int stockAfterRefund = stock();

        assertDoesNotThrow(() -> refundTransactions.syncRefundStatus(
                ORDER_ID, attemptRecord().getId(), snapshot("succeeded")));
        assertDoesNotThrow(() -> refundTransactions.syncRefundStatus(
                ORDER_ID, attemptRecord().getId(), snapshot("succeeded")));
        assertDoesNotThrow(() -> refundTransactions.syncRefundStatus(
                ORDER_ID, attemptRecord().getId(), snapshot("succeeded")));

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(stockAfterRefund, stock());
        assertEquals(1, refundRowCount());
        OrderStripeRefundAttempt refund = attemptRecord();
        assertEquals("succeeded", refund.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refund.getReconciliationState());
        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(refund.getIdempotencyKey()));
        verify(refundGateway, never()).retrieveRefund(anyString());
    }

    /**
     * 本地 PENDING 且已绑定 {@code refundId}：{@code recoverRefund()} 走
     * {@code retrieveRefund()} 回查推进，不再发起新的退款创建。
     * <p>Stripe 退回 pending 的退款（例如银行卡退款需要时间）就是这个形态：拒单时只拿到
     * {@code pending} 快照，库存已经归还，订单停在 {@code REFUNDING}，之后由恢复任务收敛。</p>
     */
    @Test
    void pendingRefundWithBoundRefundIdIsAdvancedByRetrieval() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        OrderStripeRefundAttempt pending = attemptRecord();
        assertEquals(REFUND_ID, pending.getRefundId());
        assertEquals("pending", pending.getStripeStatus());
        assertEquals(ReconciliationState.PENDING.code(), pending.getReconciliationState());
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        // 库存在拒单事务里已经归还，恢复阶段不允许再动一次
        assertEquals(STOCK_AFTER_REFUND, stock());

        when(refundGateway.retrieveRefund(REFUND_ID)).thenReturn(snapshot("succeeded"));
        refundRecoveryTask.recoverPendingRefunds();

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        OrderStripeRefundAttempt refunded = attemptRecord();
        assertEquals("succeeded", refunded.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refunded.getReconciliationState());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());

        // 退款状态已对账一致后再次恢复是空操作，不会产生新的远端调用
        assertDoesNotThrow(() -> stripeRefundService.recoverRefund(refunded));

        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(refunded.getIdempotencyKey()));
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    @Test
    void retryAfterUnknownOutcomeReusesTheSameAttemptAndIdempotencyKey() {
        refundTransactions.prepareRefund(ORDER_ID, MERCHANT_ID);
        OrderStripeRefundAttempt originalAttempt = attemptRecord();
        String originalKey = originalAttempt.getIdempotencyKey();
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(originalKey)))
                .thenThrow(new IllegalStateException("simulated request timeout"))
                .thenReturn(snapshot("pending"));

        assertThrows(IllegalStateException.class, () -> stripeRefundService.refundRejectedOrder(ORDER_ID));
        assertEquals(1, attemptCount());
        assertEquals(originalAttempt.getId(), attemptRecord().getId());
        assertNull(attemptRecord().getRefundId());
        assertEquals(RefundStatus.PENDING.code(), refundRecord().getStatus());

        stripeRefundService.refundRejectedOrder(ORDER_ID);

        assertEquals(1, attemptCount());
        assertEquals(originalAttempt.getId(), attemptRecord().getId());
        assertEquals(originalKey, attemptRecord().getIdempotencyKey());
        assertEquals(REFUND_ID, attemptRecord().getRefundId());
        assertEquals("pending", attemptRecord().getStripeStatus());
        verify(refundGateway, times(2)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(originalKey));
    }

    @Test
    void retryableConfirmedFailureCreatesANewAttemptAndUsesANewIdempotencyKey() {
        AtomicInteger requestCount = new AtomicInteger();
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenAnswer(invocation -> {
                    String idempotencyKey = invocation.getArgument(2);
                    if (requestCount.incrementAndGet() == 1) {
                        assertEquals(1, attemptCount());
                        assertEquals(idempotencyKey, attemptRecord().getIdempotencyKey());
                        return snapshot(REFUND_ID, "failed", RETRYABLE_FAILURE_REASON);
                    }

                    assertEquals(2, attemptCount());
                    assertEquals(idempotencyKey, attemptRecord().getIdempotencyKey());
                    return snapshot(NEXT_REFUND_ID, "succeeded", null);
                });
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        OrderStripeRefundAttempt failedAttempt = attemptRecord();
        assertEquals("failed", failedAttempt.getStripeStatus());
        assertEquals(RETRYABLE_FAILURE_REASON, failedAttempt.getFailureReason());
        assertEquals(ReconciliationState.RECONCILED.code(), failedAttempt.getReconciliationState());
        assertEquals(RefundStatus.PENDING.code(), refundRecord().getStatus());
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());

        // A 已确认失败时，先前在途的 pending 结果不能挡住下一次业务尝试。
        assertEquals(RefundSyncResult.CONFLICT, refundTransactions.syncRefundStatus(
                ORDER_ID, failedAttempt.getId(), snapshot(REFUND_ID, "pending", null)));
        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefundAttempt succeededAttempt = attemptRecord();
        assertEquals(2, attemptCount());
        assertNotNull(succeededAttempt.getId());
        assertNotEquals(failedAttempt.getId(), succeededAttempt.getId());
        assertNotEquals(failedAttempt.getIdempotencyKey(), succeededAttempt.getIdempotencyKey());
        assertEquals(NEXT_REFUND_ID, succeededAttempt.getRefundId());
        assertEquals("succeeded", succeededAttempt.getStripeStatus());
        assertEquals("failed", refundAttemptMapper.selectById(failedAttempt.getId()).getStripeStatus());
        assertTrue(refundAttemptMapper.selectFailedAttempts(10).isEmpty());
        assertEquals(RefundStatus.SUCCEEDED.code(), refundRecord().getStatus());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());

        ArgumentCaptor<String> idempotencyKeys = ArgumentCaptor.forClass(String.class);
        verify(refundGateway, times(2)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), idempotencyKeys.capture());
        assertEquals(List.of(failedAttempt.getIdempotencyKey(), succeededAttempt.getIdempotencyKey()),
                idempotencyKeys.getAllValues());
    }

    @Test
    void concurrentRetryDecisionsFromTheSameFailedAttemptCreateOnlyOneNextAttempt() throws Exception {
        refundTransactions.prepareRefund(ORDER_ID, MERCHANT_ID);
        OrderStripeRefundAttempt failedAttempt = attemptRecord();
        refundTransactions.syncRefundStatus(ORDER_ID, failedAttempt.getId(),
                snapshot(REFUND_ID, "failed", RETRYABLE_FAILURE_REASON));

        CountDownLatch decisionsReady = new CountDownLatch(2);
        CountDownLatch startDecisions = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RefundExecution> firstDecision = executor.submit(() -> {
                decisionsReady.countDown();
                if (!startDecisions.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for the retry decision start");
                }
                return refundTransactions.prepareRefundExecution(ORDER_ID, failedAttempt.getId());
            });
            Future<RefundExecution> secondDecision = executor.submit(() -> {
                decisionsReady.countDown();
                if (!startDecisions.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for the retry decision start");
                }
                return refundTransactions.prepareRefundExecution(ORDER_ID, failedAttempt.getId());
            });

            assertTrue(decisionsReady.await(10, TimeUnit.SECONDS));
            startDecisions.countDown();
            RefundExecution first = firstDecision.get(10, TimeUnit.SECONDS);
            RefundExecution second = secondDecision.get(10, TimeUnit.SECONDS);

            assertTrue((first == null) != (second == null));
            RefundExecution createdAttempt = first == null ? second : first;
            assertNotNull(createdAttempt);
            assertNotEquals(failedAttempt.getId(), createdAttempt.attempt().getId());
            assertNotEquals(failedAttempt.getIdempotencyKey(), createdAttempt.attempt().getIdempotencyKey());
            assertEquals(2, attemptCount());
            assertEquals(createdAttempt.attempt().getId(), attemptRecord().getId());
            verifyNoInteractions(refundGateway);
        } finally {
            startDecisions.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lateResponseFromOldAttemptCannotOverwriteTheNewRefundBusinessState() throws Exception {
        refundTransactions.prepareRefund(ORDER_ID, MERCHANT_ID);
        OrderStripeRefundAttempt originalAttempt = attemptRecord();
        CountDownLatch originalRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseOriginalResponse = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(2);
                    if (originalAttempt.getIdempotencyKey().equals(key)) {
                        originalRequestStarted.countDown();
                        if (!releaseOriginalResponse.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to release the first response");
                        }
                        return snapshot(REFUND_ID, "failed", RETRYABLE_FAILURE_REASON);
                    }
                    return snapshot(NEXT_REFUND_ID, "succeeded", null);
                });

        Future<?> delayedAttempt = executor.submit(() -> stripeRefundService.refundRejectedOrder(ORDER_ID));
        try {
            assertTrue(originalRequestStarted.await(10, TimeUnit.SECONDS));

            refundTransactions.syncRefundStatus(ORDER_ID, originalAttempt.getId(),
                    snapshot(REFUND_ID, "failed", RETRYABLE_FAILURE_REASON));
            stripeRefundService.refundRejectedOrder(ORDER_ID);

            OrderStripeRefundAttempt currentAttempt = attemptRecord();
            assertNotEquals(originalAttempt.getId(), currentAttempt.getId());
            assertEquals("succeeded", currentAttempt.getStripeStatus());
            assertEquals(RefundStatus.SUCCEEDED.code(), refundRecord().getStatus());
            assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());

            releaseOriginalResponse.countDown();
            delayedAttempt.get(10, TimeUnit.SECONDS);

            assertEquals(2, attemptCount());
            assertEquals("failed", refundAttemptMapper.selectById(originalAttempt.getId()).getStripeStatus());
            assertTrue(refundAttemptMapper.selectFailedAttempts(10).isEmpty());
            assertEquals(currentAttempt.getId(), attemptRecord().getId());
            assertEquals("succeeded", attemptRecord().getStripeStatus());
            assertEquals(RefundStatus.SUCCEEDED.code(), refundRecord().getStatus());
            assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        } finally {
            releaseOriginalResponse.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void stalePendingSnapshotCannotReopenCompletedRefund() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        // 恢复任务扫描到 PENDING 后，另一份快照先完成了退款。
        OrderStripeRefundAttempt scanned = attemptRecord();
        assertEquals(RefundSyncResult.REFUNDED,
                refundTransactions.syncRefundStatus(ORDER_ID, scanned.getId(), snapshot("succeeded")));
        when(refundGateway.retrieveRefund(REFUND_ID))
                .thenReturn(snapshot("pending"));
        stripeRefundService.recoverRefund(scanned);

        // 旧的 pending 快照不能覆盖已经回写的成功结果，也不能重新安排重试。
        OrderStripeRefundAttempt completed = attemptRecord();
        assertEquals("succeeded", completed.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), completed.getReconciliationState());
        assertEquals(0, completed.getRetryCount());
        assertNull(completed.getNextRetryAt());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        verify(refundGateway, never()).retrieveRefund(REFUND_ID);
    }

    @Test
    void expiredUnboundRefundMovesToReviewWithoutCallingStripe() {
        refundTransactions.prepareRefund(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refund_attempts SET create_time = ? WHERE order_id = ?",
                LocalDateTime.now().minusDays(2), ORDER_ID);

        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefundAttempt reviewed = attemptRecord();
        assertEquals(ReconciliationState.REVIEW.code(), reviewed.getReconciliationState());
        assertNull(reviewed.getRefundId());
        assertEquals(RefundStatus.REVIEW.code(), refundRecord().getStatus());
        verifyNoInteractions(refundGateway);
    }

    @Test
    void expiredStaleSnapshotDoesNotMoveBoundPendingRefundToReview() {
        refundTransactions.prepareRefund(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refund_attempts SET create_time = ? WHERE order_id = ?",
                LocalDateTime.now().minusDays(2), ORDER_ID);
        OrderStripeRefundAttempt scanned = attemptRecord();
        assertNull(scanned.getRefundId());
        refundTransactions.syncRefundStatus(ORDER_ID, scanned.getId(), snapshot("pending"));
        when(refundGateway.retrieveRefund(REFUND_ID)).thenReturn(snapshot("pending"));

        stripeRefundService.recoverRefund(scanned);

        OrderStripeRefundAttempt current = attemptRecord();
        assertEquals(ReconciliationState.PENDING.code(), current.getReconciliationState());
        assertEquals(REFUND_ID, current.getRefundId());
        assertEquals("pending", current.getStripeStatus());
        assertEquals(RefundStatus.PENDING.code(), refundRecord().getStatus());
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
        verify(refundGateway, never()).createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString());
    }

    @Test
    void expiredStaleSnapshotDoesNotMoveCompletedRefundToReview() {
        refundTransactions.prepareRefund(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refund_attempts SET create_time = ? WHERE order_id = ?",
                LocalDateTime.now().minusDays(2), ORDER_ID);
        OrderStripeRefundAttempt scanned = attemptRecord();
        assertNull(scanned.getRefundId());
        refundTransactions.syncRefundStatus(ORDER_ID, scanned.getId(), snapshot("succeeded"));

        stripeRefundService.recoverRefund(scanned);

        OrderStripeRefundAttempt current = attemptRecord();
        assertEquals(ReconciliationState.RECONCILED.code(), current.getReconciliationState());
        assertEquals(REFUND_ID, current.getRefundId());
        assertEquals("succeeded", current.getStripeStatus());
        assertEquals(RefundStatus.SUCCEEDED.code(), refundRecord().getStatus());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        verifyNoInteractions(refundGateway);
    }

    @Test
    void pendingRecoveryFailureSchedulesRetryAndMatchingSnapshotCompletesReconciliation() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refund_attempts SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);
        when(refundGateway.retrieveRefund(REFUND_ID))
                .thenThrow(new IllegalStateException("simulated temporary retrieve failure"))
                .thenReturn(snapshot("succeeded"));

        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefundAttempt scheduled = attemptRecord();
        assertEquals("pending", scheduled.getStripeStatus());
        assertEquals(ReconciliationState.PENDING.code(), scheduled.getReconciliationState());
        assertEquals(1, scheduled.getRetryCount());
        assertTrue(scheduled.getNextRetryAt().isAfter(LocalDateTime.now()));
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);

        jdbc.update("UPDATE order_stripe_refund_attempts SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);
        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefundAttempt reconciled = attemptRecord();
        assertEquals("succeeded", reconciled.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), reconciled.getReconciliationState());
        assertNull(reconciled.getNextRetryAt());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        refundRecoveryTask.recoverPendingRefunds();
        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(reconciled.getIdempotencyKey()));
        verify(refundGateway, times(2)).retrieveRefund(REFUND_ID);
    }

    @Test
    void recoveryFailureAfterConcurrentCompletionKeepsCompleteAndDoesNotScheduleRetry() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refund_attempts SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);
        when(refundGateway.retrieveRefund(REFUND_ID)).thenAnswer(invocation -> {
            refundTransactions.syncRefundStatus(ORDER_ID, attemptRecord().getId(), snapshot("succeeded"));
            throw new IllegalStateException("simulated retrieve failure after concurrent completion");
        });

        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefundAttempt completed = attemptRecord();
        assertEquals("succeeded", completed.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), completed.getReconciliationState());
        assertEquals(0, completed.getRetryCount());
        assertNull(completed.getNextRetryAt());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    @Test
    void pendingRecoveryRetryLimitMovesToReviewAndExistingReviewRemainsManual() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refund_attempts SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);

        jdbc.update("UPDATE order_stripe_refund_attempts SET retry_count = ? WHERE order_id = ?",
                PaymentPolicy.MAX_RECOVERY_ATTEMPTS - 1, ORDER_ID);
        when(refundGateway.retrieveRefund(REFUND_ID))
                .thenThrow(new IllegalStateException("simulated final recovery failure"));
        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefundAttempt exhausted = attemptRecord();
        assertEquals("pending", exhausted.getStripeStatus());
        assertEquals(REFUND_ID, exhausted.getRefundId());
        assertEquals(ReconciliationState.REVIEW.code(), exhausted.getReconciliationState());
        assertEquals(PaymentPolicy.MAX_RECOVERY_ATTEMPTS, exhausted.getRetryCount());
        assertNull(exhausted.getNextRetryAt());
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);

        refundTransactions.scheduleConflictRetry(ORDER_ID, exhausted.getId());
        assertEquals(PaymentPolicy.MAX_RECOVERY_ATTEMPTS, attemptRecord().getRetryCount());
        assertEquals(ReconciliationState.REVIEW.code(), attemptRecord().getReconciliationState());
        refundRecoveryTask.recoverPendingRefunds();
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    // ------------------------------------------------------------------
    // 第三轮：故障恢复
    // ------------------------------------------------------------------

    /**
     * 故障注入：Stripe 创建退款成功，但本地 {@code syncRefundStatus} 落库失败。
     *
     * <p>用 CHECK 约束让「把退款写成 succeeded」这一步必然失败，制造出
     * "远端已有退款、本地事务回滚" 的真实不一致；此时本地必须仍然停在
     * {@code PENDING} 且没有绑定 {@code refundId}（绑定和状态写在同一个事务里），
     * 恢复任务再用同一个幂等键重新走一遍创建，Stripe 按幂等键返回同一笔退款，
     * 因此不会出现第二笔退款。</p>
     */
    @Test
    void localSyncFailureLeavesPendingThenRecoveryReconcilesWithoutASecondRefund() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("succeeded"));
        jdbc.execute("""
                ALTER TABLE order_stripe_refund_attempts
                ADD CONSTRAINT reject_terminal_refund_status
                CHECK (stripe_status IS NULL OR stripe_status <> 'succeeded')
                """);

        // 同步阶段抛异常会被拒单链路的异步任务吞掉，但事务必须整体回滚
        assertDoesNotThrow(() -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));

        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        OrderStripeRefundAttempt pending = attemptRecord();
        assertNotNull(pending);
        assertNull(pending.getRefundId(), "状态与绑定写在同一个事务里，回滚后不应留下 refundId");
        assertNull(pending.getStripeStatus());
        assertEquals(ReconciliationState.PENDING.code(), pending.getReconciliationState());
        // 库存在拒单事务内已归还并提交，不能因为退款同步失败而回滚成预扣状态
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());
        String originalKey = pending.getIdempotencyKey();
        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(originalKey));

        jdbc.execute("ALTER TABLE order_stripe_refund_attempts DROP CONSTRAINT reject_terminal_refund_status");
        refundRecoveryTask.recoverPendingRefunds();

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        OrderStripeRefundAttempt refunded = attemptRecord();
        assertEquals(REFUND_ID, refunded.getRefundId());
        assertEquals("succeeded", refunded.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refunded.getReconciliationState());
        // 库存只归还了一次：拒单时归还，恢复阶段不再归还
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());

        // 本地没有 refundId 时恢复同一请求，两次用的是同一个幂等键
        // → Stripe 返回同一笔退款，不会产生第二笔
        ArgumentCaptor<String> idempotencyKeys = ArgumentCaptor.forClass(String.class);
        verify(refundGateway, times(2)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), idempotencyKeys.capture());
        assertEquals(List.of(originalKey, originalKey), idempotencyKeys.getAllValues());
        verify(refundGateway, never()).retrieveRefund(anyString());
    }

    // ------------------------------------------------------------------
    // 第四轮：退款 Webhook 事件
    // ------------------------------------------------------------------

    /** 退款成功的 Webhook 把本地 pending 推到 REFUNDED，并写下事件回执。 */
    @Test
    void refundWebhookEventAdvancesPendingRefundAndRecordsReceipt() {
        pendingRefund();
        when(refundGateway.retrieveRefund(REFUND_ID)).thenReturn(snapshot("succeeded"));

        stripeRefundService.processWebhookRefundEvent(refundEvent("evt_refund_succeeded", "refund.updated"));

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        OrderStripeRefundAttempt refund = attemptRecord();
        assertEquals("succeeded", refund.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refund.getReconciliationState());
        assertEquals(1, refundEventCount("evt_refund_succeeded"));
        // 事件只推进状态，不会再下一笔退款
        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(refund.getIdempotencyKey()));
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    /** 同一事件重复投递：第二次直接短路，不再回查远端，也不再写第二份回执。 */
    @Test
    void repeatedRefundWebhookEventIsIgnoredAfterTheFirstReceipt() {
        pendingRefund();
        when(refundGateway.retrieveRefund(REFUND_ID)).thenReturn(snapshot("succeeded"));

        stripeRefundService.processWebhookRefundEvent(refundEvent("evt_refund_dup", "refund.updated"));
        stripeRefundService.processWebhookRefundEvent(refundEvent("evt_refund_dup", "refund.updated"));

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(1, refundEventCount("evt_refund_dup"));
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    /**
     * 状态写入失败时事务整体回滚，事件回执必须一起消失。
     * <p>否则 Stripe 重投会被自己的去重逻辑挡掉，这笔退款永远停在 pending。</p>
     */
    @Test
    void failedRefundWebhookDoesNotLeaveReceiptSoTheRetryIsStillProcessed() {
        pendingRefund();
        when(refundGateway.retrieveRefund(REFUND_ID)).thenReturn(snapshot("succeeded"));
        jdbc.execute("""
                ALTER TABLE order_stripe_refund_attempts
                ADD CONSTRAINT reject_webhook_terminal_status
                CHECK (stripe_status IS NULL OR stripe_status <> 'succeeded')
                """);

        assertThrows(RuntimeException.class, () ->
                stripeRefundService.processWebhookRefundEvent(refundEvent("evt_refund_retry", "refund.updated")));

        assertEquals(0, refundEventCount("evt_refund_retry"));
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());

        jdbc.execute("ALTER TABLE order_stripe_refund_attempts DROP CONSTRAINT reject_webhook_terminal_status");
        stripeRefundService.processWebhookRefundEvent(refundEvent("evt_refund_retry", "refund.updated"));

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(1, refundEventCount("evt_refund_retry"));
    }

    /** 指向另一笔退款对象的事件不属于本订单：既不改本地状态，也不留回执。 */
    @Test
    void refundWebhookForAnotherRefundObjectIsIgnoredWithoutReceipt() {
        pendingRefund();

        stripeRefundService.processWebhookRefundEvent(new StripeWebhookEvent("evt_refund_foreign",
                "refund.updated", false, null,
                new StripeRefundSnapshot("re_other_701", PAYMENT_INTENT_ID, AMOUNT_MINOR, CURRENCY, "succeeded", null)));

        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        assertEquals("pending", attemptRecord().getStripeStatus());
        assertEquals(0, refundEventCount("evt_refund_foreign"));
        verify(refundGateway, never()).retrieveRefund(anyString());
    }

    /**
     * 退款失败的 Webhook 必须把本地状态同步成 failed（钱没退出去），
     * 而不是只记一条回执了事。订单停在 REFUNDING，等人工决定重试还是回滚。
     */
    @Test
    void refundFailedWebhookSyncsLocalRefundToFailed() {
        pendingRefund();
        when(refundGateway.retrieveRefund(REFUND_ID))
                .thenReturn(snapshot("failed", "expired_or_canceled_card"));

        stripeRefundService.processWebhookRefundEvent(refundEvent("evt_refund_failed", "refund.failed"));

        OrderStripeRefundAttempt after = attemptRecord();
        assertEquals("failed", after.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), after.getReconciliationState());
        // 失败原因要落库：后续按原因判断是否值得自动重试
        assertEquals("expired_or_canceled_card", after.getFailureReason());
        assertEquals(REFUND_ID, after.getRefundId());
        // 钱没退成，订单不能被推进到 REFUNDED
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        assertEquals(1, refundEventCount("evt_refund_failed"));

        // 失败巡检查询能看到这条记录，且它只读，不改状态
        List<OrderStripeRefundAttempt> failed = refundAttemptMapper.selectFailedAttempts(10);
        assertEquals(1, failed.size());
        assertEquals(ORDER_ID, failed.get(0).getOrderId());
        assertEquals("expired_or_canceled_card", failed.get(0).getFailureReason());
        assertEquals("failed", attemptRecord().getStripeStatus());
        assertEquals(RefundStatus.REVIEW.code(), refundRecord().getStatus());

        stripeRefundService.refundRejectedOrder(ORDER_ID);
        assertEquals(1, attemptCount());
        verify(refundGateway, times(1)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), eq(attemptRecord().getIdempotencyKey()));
    }

    @Test
    void lateWebhookForFailedOldAttemptCannotChangeSuccessfulCurrentAttempt() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot(REFUND_ID, "failed", RETRYABLE_FAILURE_REASON))
                .thenReturn(snapshot(NEXT_REFUND_ID, "succeeded", null));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        OrderStripeRefundAttempt oldAttempt = attemptRecord();
        assertEquals("failed", oldAttempt.getStripeStatus());

        stripeRefundService.refundRejectedOrder(ORDER_ID);
        OrderStripeRefundAttempt currentAttempt = attemptRecord();
        assertEquals("succeeded", currentAttempt.getStripeStatus());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());

        String lateFailureReason = "late_webhook_failure_reason";
        StripeRefundSnapshot failedSnapshot = snapshot(REFUND_ID, "failed", lateFailureReason);
        when(refundGateway.retrieveRefund(REFUND_ID)).thenReturn(failedSnapshot);
        StripeWebhookEvent failedEvent = new StripeWebhookEvent(
                "evt_refund_failed_after_success", "refund.failed", false, null, failedSnapshot);

        stripeRefundService.processWebhookRefundEvent(failedEvent);
        stripeRefundService.processWebhookRefundEvent(failedEvent);

        OrderStripeRefundAttempt afterWebhook = attemptRecord();
        assertEquals(NEXT_REFUND_ID, afterWebhook.getRefundId());
        assertEquals("succeeded", afterWebhook.getStripeStatus());
        assertEquals(RefundStatus.SUCCEEDED.code(), refundRecord().getStatus());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundEventCount(failedEvent.id()));
        OrderStripeRefundAttempt historicalAttempt = refundAttemptMapper.selectById(oldAttempt.getId());
        assertEquals("failed", historicalAttempt.getStripeStatus());
        assertEquals(lateFailureReason, historicalAttempt.getFailureReason());
        assertEquals(ReconciliationState.RECONCILED.code(), historicalAttempt.getReconciliationState());
        assertTrue(refundAttemptMapper.selectFailedAttempts(10).isEmpty());
        verify(refundGateway, times(2)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString());
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    @Test
    void unboundAttemptWebhookMustMatchItsAttemptMetadata() {
        refundTransactions.prepareRefund(ORDER_ID, MERCHANT_ID);
        OrderStripeRefundAttempt failedAttempt = attemptRecord();
        refundTransactions.syncRefundStatus(ORDER_ID, failedAttempt.getId(),
                snapshot(REFUND_ID, "failed", RETRYABLE_FAILURE_REASON));

        RefundExecution execution = refundTransactions.prepareRefundExecution(ORDER_ID, failedAttempt.getId());
        assertNotNull(execution);
        OrderStripeRefundAttempt unboundAttempt = execution.attempt();
        assertNull(unboundAttempt.getRefundId());
        assertEquals(2, attemptCount());

        StripeRefundSnapshot wrongMetadata = snapshot(
                NEXT_REFUND_ID, "succeeded", null, "refund_attempt_unmatched_key");
        stripeRefundService.processWebhookRefundEvent(new StripeWebhookEvent(
                "evt_unbound_refund_wrong_key", "refund.updated", false, null, wrongMetadata));

        assertEquals(RefundStatus.PENDING.code(), refundRecord().getStatus());
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        assertNull(attemptRecord().getRefundId());
        assertEquals(0, refundEventCount("evt_unbound_refund_wrong_key"));
        verifyNoInteractions(refundGateway);

        StripeRefundSnapshot matchedMetadata = snapshot(
                NEXT_REFUND_ID, "succeeded", null, unboundAttempt.getIdempotencyKey());
        when(refundGateway.retrieveRefund(NEXT_REFUND_ID)).thenReturn(matchedMetadata);
        stripeRefundService.processWebhookRefundEvent(new StripeWebhookEvent(
                "evt_unbound_refund_matching_key", "refund.updated", false, null, matchedMetadata));

        assertEquals(unboundAttempt.getId(), attemptRecord().getId());
        assertEquals(NEXT_REFUND_ID, attemptRecord().getRefundId());
        assertEquals("succeeded", attemptRecord().getStripeStatus());
        assertEquals("failed", refundAttemptMapper.selectById(failedAttempt.getId()).getStripeStatus());
        assertEquals(RefundStatus.SUCCEEDED.code(), refundRecord().getStatus());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(1, refundEventCount("evt_unbound_refund_matching_key"));
        verify(refundGateway, times(1)).retrieveRefund(NEXT_REFUND_ID);
    }

    /** 准备一条已绑定退款单号、仍在等待远端的本地退款记录。 */
    private void pendingRefund() {
        when(refundGateway.createRefund(eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), anyString()))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        OrderStripeRefundAttempt pending = attemptRecord();
        assertEquals(REFUND_ID, pending.getRefundId());
        assertEquals("pending", pending.getStripeStatus());
        assertEquals(ReconciliationState.PENDING.code(), pending.getReconciliationState());
    }

    // ------------------------------------------------------------------
    // 数据准备与断言辅助
    // ------------------------------------------------------------------

    /** 把订单准备成「Stripe 已收款、等待商家处理」的形态，库存已按明细扣减。 */
    private void insertPaidOrderWithSucceededPayment() {
        LocalDateTime created = LocalDateTime.now().minusMinutes(2);
        jdbc.update("""
                INSERT INTO orders (
                    id, order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
                    total_amount, status, receiver_name, receiver_phone, receiver_address,
                    create_time, timeout_cancel_available_at, update_time, pay_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ORDER_ID, "ORD-701", USER_ID, "request-701", "fingerprint", MERCHANT_ID, "测试商家",
                new BigDecimal("123.45"), OrderStatusEnum.PAID.getCode(), "测试用户", "13000000000",
                "测试地址", created, created.plusMinutes(30), created, created);

        jdbc.update("""
                INSERT INTO order_stripe_payments (
                    order_id, payment_intent_id, amount_minor, currency, stripe_status,
                    idempotency_key, create_time, update_time, reconciliation_state
                ) VALUES (?, ?, ?, ?, 'succeeded', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'RECONCILED')
                """, ORDER_ID, PAYMENT_INTENT_ID, AMOUNT_MINOR, CURRENCY, "order_pay_" + ORDER_ID);

        // 下单已扣 3 件，因此这里只剩 7 件
        jdbc.update("""
                INSERT INTO product (id, stock, stock_version, version, status, is_deleted)
                VALUES (?, ?, 0, 0, 0, 0)
                """, PRODUCT_ID, STOCK_AFTER_ORDER);
        jdbc.update("""
                INSERT INTO order_item (
                    id, order_id, product_id, product_name, product_price, quantity, subtotal,
                    stock_version, product_picture
                ) VALUES (?, ?, ?, '测试商品', 12.34, ?, 37.02, 0, NULL)
                """, ORDER_ITEM_ID, ORDER_ID, PRODUCT_ID, ITEM_QUANTITY);
    }

    private void recreateSchema() {
        // 依赖顺序：先删引用方，再删被引用方
        jdbc.execute("DROP TABLE IF EXISTS stripe_refund_events");
        jdbc.execute("DROP TABLE IF EXISTS order_stripe_refund_attempts");
        jdbc.execute("DROP TABLE IF EXISTS order_stripe_refunds");
        jdbc.execute("DROP TABLE IF EXISTS order_stripe_payments");
        jdbc.execute("DROP TABLE IF EXISTS order_item");
        jdbc.execute("DROP TABLE IF EXISTS product");
        jdbc.execute("DROP TABLE IF EXISTS orders");

        jdbc.execute("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    order_no VARCHAR(32) NOT NULL,
                    user_id BIGINT NOT NULL,
                    request_id VARCHAR(64) NOT NULL,
                    request_hash VARCHAR(64) NOT NULL,
                    merchant_id BIGINT NOT NULL,
                    merchant_name VARCHAR(255) NOT NULL,
                    total_amount DECIMAL(10, 2) NOT NULL,
                    delivery_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    status INT NOT NULL,
                    cancel_source INT,
                    receiver_name VARCHAR(64) NOT NULL,
                    receiver_phone VARCHAR(32) NOT NULL,
                    receiver_address VARCHAR(255) NOT NULL,
                    remark VARCHAR(255),
                    create_time TIMESTAMP NOT NULL,
                    timeout_cancel_available_at TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL,
                    finish_time TIMESTAMP,
                    original_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
                    pay_time TIMESTAMP,
                    payment_started_at TIMESTAMP,
                    merchant_seen BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE order_stripe_payments (
                    order_id BIGINT PRIMARY KEY,
                    payment_intent_id VARCHAR(255) UNIQUE,
                    amount_minor BIGINT NOT NULL,
                    currency VARCHAR(3) NOT NULL,
                    stripe_status VARCHAR(64),
                    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL,
                    reconciliation_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE order_stripe_refunds (
                    order_id BIGINT PRIMARY KEY,
                    refund_id VARCHAR(255) UNIQUE,
                    amount_minor BIGINT NOT NULL,
                    currency VARCHAR(10) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE order_stripe_refund_attempts (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    refund_id VARCHAR(255) UNIQUE,
                    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                    stripe_status VARCHAR(32),
                    reconciliation_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    failure_reason VARCHAR(64),
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_at TIMESTAMP NULL,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (order_id) REFERENCES order_stripe_refunds(order_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE stripe_refund_events (
                    event_id VARCHAR(255) PRIMARY KEY,
                    refund_id VARCHAR(255) NOT NULL,
                    event_type VARCHAR(100) NOT NULL,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("CREATE TABLE product (id BIGINT PRIMARY KEY, stock INT, stock_version INT, "
                + "version INT, status INT, is_deleted INT)");
        jdbc.execute("CREATE TABLE order_item (id BIGINT PRIMARY KEY, order_id BIGINT, "
                + "product_id BIGINT, product_name VARCHAR(100), product_price DECIMAL(10,2), "
                + "quantity INT, subtotal DECIMAL(10,2), stock_version INT, product_picture VARCHAR(100))");
    }

    private int orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", Integer.class, ORDER_ID);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, PRODUCT_ID);
    }

    private int refundRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_stripe_refunds", Integer.class);
    }

    private int attemptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_stripe_refund_attempts", Integer.class);
    }

    private OrderStripeRefund refundRecord() {
        return refundMapper.selectById(ORDER_ID);
    }

    private OrderStripeRefundAttempt attemptRecord() {
        return refundAttemptMapper.selectLatestAttemptByOrderId(ORDER_ID);
    }

    private int refundEventCount(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stripe_refund_events WHERE event_id = ?",
                Integer.class, eventId);
    }

    private static StripeWebhookEvent refundEvent(String eventId, String type) {
        return new StripeWebhookEvent(eventId, type, false, null, snapshot("succeeded"));
    }

    private static StripeRefundSnapshot snapshot(String status) {
        return snapshot(status, null);
    }

    private static StripeRefundSnapshot snapshot(String status, String failureReason) {
        return new StripeRefundSnapshot(REFUND_ID, PAYMENT_INTENT_ID, AMOUNT_MINOR, CURRENCY, status, failureReason);
    }

    private static StripeRefundSnapshot snapshot(String refundId, String status, String failureReason) {
        return new StripeRefundSnapshot(refundId, PAYMENT_INTENT_ID, AMOUNT_MINOR,
                CURRENCY, status, failureReason);
    }

    private static StripeRefundSnapshot snapshot(String refundId, String status,
                                                  String failureReason, String attemptKey) {
        return new StripeRefundSnapshot(refundId, PAYMENT_INTENT_ID, AMOUNT_MINOR,
                CURRENCY, status, failureReason, attemptKey);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @EnableConfigurationProperties(StripeRefundRetryPolicy.class)
    @MapperScan(basePackageClasses = {OrderStripePaymentMapper.class, OrderStripeRefundMapper.class,
            OrderStripeRefundAttemptMapper.class,
            OrderMapper.class, OrderItemMapper.class, ProductMapper.class},
            annotationClass = Mapper.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:stripe-refund-flow;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /** 唯一的假实现：代替 Stripe HTTP，让成功/失败/只创建未落库的时序都可控。 */
        @Bean
        StripeRefundGateway stripeRefundGateway() {
            return mock(StripeRefundGateway.class);
        }

        @Bean
        OrderItemService orderItems(ProductMapper productMapper, OrderItemMapper itemMapper) {
            return new OrderItemService(new ProductCommandService(productMapper, null), itemMapper, null);
        }

        @Bean
        OrderCommandService orderCommands(OrderMapper orderMapper, OrderItemService orderItems,
                                          ObjectProvider<StripeRefundService> refundServiceProvider) {
            // 同步执行器：afterCommit 里的退款任务在当前线程跑完，测试无需等待
            return new OrderCommandService(orderMapper, orderItems, refundServiceProvider, Runnable::run);
        }

        @Bean
        StripeRefundTransactionService refundTransactionService(OrderStripeRefundMapper refundMapper,
                                                                OrderStripeRefundAttemptMapper attemptMapper,
                                                                OrderMapper orderMapper,
                                                                OrderStripePaymentMapper paymentMapper,
                                                                StripeRefundEventMapper refundEventMapper,
                                                                StripeRefundRetryPolicy retryPolicy) {
            return new StripeRefundTransactionService(
                    refundMapper, attemptMapper, orderMapper, paymentMapper, refundEventMapper, retryPolicy);
        }

        @Bean
        StripeRefundService stripeRefundService(StripeRefundTransactionService transactions,
                                                StripeRefundGateway gateway,
                                                OrderStripeRefundAttemptMapper attemptMapper) {
            return new StripeRefundService(transactions, gateway, attemptMapper);
        }

        @Bean
        StripeRefundRecoveryTask refundRecoveryTask(OrderStripeRefundAttemptMapper attemptMapper,
                                                   StripeRefundService refundService) {
            return new StripeRefundRecoveryTask(attemptMapper, refundService);
        }
    }
}
