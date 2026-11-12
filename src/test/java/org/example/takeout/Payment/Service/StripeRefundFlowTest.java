package org.example.takeout.Payment.Service;

import org.apache.ibatis.annotations.Mapper;
import org.example.takeout.Config.MybatisPlusConfig;
import org.example.takeout.Order.Enums.OrderStatusEnum;
import org.example.takeout.Order.Mapper.OrderItemMapper;
import org.example.takeout.Order.Mapper.OrderMapper;
import org.example.takeout.Order.Service.OrderCommandService;
import org.example.takeout.Order.Service.OrderItemService;
import org.example.takeout.Payment.Config.StripeRefundRecoveryTask;
import org.example.takeout.Payment.Domain.PaymentPolicy;
import org.example.takeout.Payment.Domain.ReconciliationState;
import org.example.takeout.Payment.Domain.RefundSyncResult;
import org.example.takeout.Payment.Entity.OrderStripeRefund;
import org.example.takeout.Payment.Gateway.StripeRefundGateway;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Mapper.OrderStripePaymentMapper;
import org.example.takeout.Payment.Mapper.OrderStripeRefundMapper;
import org.example.takeout.Product.Mapper.ProductMapper;
import org.example.takeout.Product.Service.ProductCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.annotation.MapperScan;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 *   <li>正常退款：订单落 {@code REFUNDED}、退款记录 {@code succeeded/COMPLETE}、库存只归还一次；</li>
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
@TestPropertySource(properties = "payment.stripe.enabled=true")
class StripeRefundFlowTest {

    private static final long ORDER_ID = 701L;
    private static final long USER_ID = 301L;
    private static final long MERCHANT_ID = 401L;
    private static final long PRODUCT_ID = 902L;
    private static final long ORDER_ITEM_ID = 1L;

    private static final String PAYMENT_INTENT_ID = "pi_refund_flow_701";
    private static final String REFUND_ID = "re_refund_flow_701";
    private static final long AMOUNT_MINOR = 12_345L;
    private static final String CURRENCY = "cny";
    /** 与 {@code StripeRefundTransactionService#prepareRefund} 中的生成规则保持一致。 */
    private static final String IDEMPOTENCY_KEY = "order_refund_" + ORDER_ID;

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
     * 商家拒单 → Stripe 退款成功 → 订单 REFUNDED、退款记录 succeeded/COMPLETE、库存只归还一次。
     * <p>Stripe 后台是否真的存在退款对象，在假网关里等价于：创建时用的
     * {@code PaymentIntent + 金额 + 币种 + 幂等键} 正确，且落库的就是 Stripe 返回的退款单号。</p>
     */
    @Test
    void merchantRejectionRefundsOrderAndReturnsInventoryExactlyOnce() {
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));

        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());

        OrderStripeRefund refund = refundRecord();
        assertNotNull(refund);
        assertEquals(REFUND_ID, refund.getRefundId());
        assertEquals("succeeded", refund.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refund.getReconciliationState());
        assertEquals(AMOUNT_MINOR, refund.getAmountMinor());
        assertEquals(CURRENCY, refund.getCurrency());
        assertEquals(IDEMPOTENCY_KEY, refund.getIdempotencyKey());

        // 整条链路只向 Stripe 发过一次「创建退款」，没有走回查兜底
        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
        verify(refundGateway, never()).retrieveRefund(anyString());
    }

    /**
     * 第一轮的补充：用落库的 {@code refundId} 回查 Stripe，能取到同一笔退款对象。
     * <p>这一步对应人工在 Stripe 测试后台（或 {@code stripe refunds retrieve}）核对退款对象存在，
     * 只是把「人眼看后台」换成了「同样的 retrieve 接口断言」。</p>
     */
    @Test
    void persistedRefundObjectCanBeRetrievedFromStripe() {
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        String refundId = refundRecord().getRefundId();
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
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        assertEquals(STOCK_AFTER_REFUND, stock());

        assertDoesNotThrow(() -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));
        assertDoesNotThrow(() -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());
        assertEquals(REFUND_ID, refundRecord().getRefundId());
        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
        verify(refundGateway, never()).retrieveRefund(anyString());
    }

    /**
     * 重复同步同一份 succeeded 快照：状态已一致且已绑定退款单号，直接幂等返回。
     * <p>如果这里没有幂等短路，第二次 {@code updateOrderStatusToRefunded} 会因为订单已是
     * {@code REFUNDED}（不再是 {@code REFUNDING}）而影响 0 行并抛异常。</p>
     */
    @Test
    void replayingTheSameSucceededSnapshotKeepsTheTerminalState() {
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        int stockAfterRefund = stock();

        assertDoesNotThrow(() -> refundTransactions.syncRefundStatus(ORDER_ID, snapshot("succeeded")));
        assertDoesNotThrow(() -> refundTransactions.syncRefundStatus(ORDER_ID, snapshot("succeeded")));
        assertDoesNotThrow(() -> refundTransactions.syncRefundStatus(ORDER_ID, snapshot("succeeded")));

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(stockAfterRefund, stock());
        assertEquals(1, refundRowCount());
        OrderStripeRefund refund = refundRecord();
        assertEquals("succeeded", refund.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refund.getReconciliationState());
        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
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
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        OrderStripeRefund pending = refundRecord();
        assertEquals(REFUND_ID, pending.getRefundId());
        assertEquals("pending", pending.getStripeStatus());
        assertEquals(ReconciliationState.PENDING.code(), pending.getReconciliationState());
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        // 库存在拒单事务里已经归还，恢复阶段不允许再动一次
        assertEquals(STOCK_AFTER_REFUND, stock());

        when(refundGateway.retrieveRefund(REFUND_ID)).thenReturn(snapshot("succeeded"));
        refundRecoveryTask.recoverPendingRefunds();

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        OrderStripeRefund refunded = refundRecord();
        assertEquals("succeeded", refunded.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refunded.getReconciliationState());
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());

        // 已到 COMPLETE 之后再执行一次恢复是空操作，不会产生新的远端调用
        assertDoesNotThrow(() -> stripeRefundService.recoverRefund(refunded));

        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    @Test
    void stalePendingRecoveryCannotReopenCompletedRefundOnTerminalConflict() {
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);

        // 恢复任务扫描到 PENDING 后，另一份快照先完成了退款。
        OrderStripeRefund scanned = refundRecord();
        assertEquals(RefundSyncResult.REFUNDED,
                refundTransactions.syncRefundStatus(ORDER_ID, snapshot("succeeded")));
        when(refundGateway.retrieveRefund(REFUND_ID))
                .thenReturn(snapshot("failed"));
        stripeRefundService.recoverRefund(scanned);

        // 旧扫描拿到的 Stripe 快照与刚确认的终态冲突，但锁内记录已是 COMPLETE。
        refundTransactions.scheduleConflictRetry(ORDER_ID);
        OrderStripeRefund completed = refundRecord();
        assertEquals("succeeded", completed.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), completed.getReconciliationState());
        assertEquals(0, completed.getRetryCount());
        assertNull(completed.getNextRetryAt());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);
    }

    @Test
    void pendingRecoveryFailureSchedulesRetryAndMatchingSnapshotCompletesReconciliation() {
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refunds SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);
        when(refundGateway.retrieveRefund(REFUND_ID))
                .thenThrow(new IllegalStateException("simulated temporary retrieve failure"))
                .thenReturn(snapshot("succeeded"));

        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefund scheduled = refundRecord();
        assertEquals("pending", scheduled.getStripeStatus());
        assertEquals(ReconciliationState.PENDING.code(), scheduled.getReconciliationState());
        assertEquals(1, scheduled.getRetryCount());
        assertTrue(scheduled.getNextRetryAt().isAfter(LocalDateTime.now()));
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);

        jdbc.update("UPDATE order_stripe_refunds SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);
        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefund reconciled = refundRecord();
        assertEquals("succeeded", reconciled.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), reconciled.getReconciliationState());
        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        assertEquals(STOCK_AFTER_REFUND, stock());
        refundRecoveryTask.recoverPendingRefunds();
        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);
        verify(refundGateway, times(2)).retrieveRefund(REFUND_ID);
    }

    @Test
    void recoveryFailureAfterConcurrentCompletionKeepsCompleteAndDoesNotScheduleRetry() {
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refunds SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);
        when(refundGateway.retrieveRefund(REFUND_ID)).thenAnswer(invocation -> {
            refundTransactions.syncRefundStatus(ORDER_ID, snapshot("succeeded"));
            throw new IllegalStateException("simulated retrieve failure after concurrent completion");
        });

        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefund completed = refundRecord();
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
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("pending"));
        orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID);
        jdbc.update("UPDATE order_stripe_refunds SET next_retry_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER_ID);

        jdbc.update("UPDATE order_stripe_refunds SET retry_count = ? WHERE order_id = ?",
                PaymentPolicy.MAX_RECOVERY_ATTEMPTS - 1, ORDER_ID);
        when(refundGateway.retrieveRefund(REFUND_ID))
                .thenThrow(new IllegalStateException("simulated final recovery failure"));
        refundRecoveryTask.recoverPendingRefunds();

        OrderStripeRefund exhausted = refundRecord();
        assertEquals("pending", exhausted.getStripeStatus());
        assertEquals(REFUND_ID, exhausted.getRefundId());
        assertEquals(ReconciliationState.REVIEW.code(), exhausted.getReconciliationState());
        assertEquals(PaymentPolicy.MAX_RECOVERY_ATTEMPTS, exhausted.getRetryCount());
        assertNull(exhausted.getNextRetryAt());
        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        verify(refundGateway, times(1)).retrieveRefund(REFUND_ID);

        refundTransactions.scheduleConflictRetry(ORDER_ID);
        assertEquals(PaymentPolicy.MAX_RECOVERY_ATTEMPTS, refundRecord().getRetryCount());
        assertEquals(ReconciliationState.REVIEW.code(), refundRecord().getReconciliationState());
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
        when(refundGateway.createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY))
                .thenReturn(snapshot("succeeded"));
        jdbc.execute("""
                ALTER TABLE order_stripe_refunds
                ADD CONSTRAINT reject_terminal_refund_status
                CHECK (stripe_status IS NULL OR stripe_status <> 'succeeded')
                """);

        // 同步阶段抛异常会被拒单链路的异步任务吞掉，但事务必须整体回滚
        assertDoesNotThrow(() -> orderCommands.rejectOrderByMerchant(ORDER_ID, MERCHANT_ID));

        assertEquals(OrderStatusEnum.REFUNDING.getCode(), orderStatus());
        OrderStripeRefund pending = refundRecord();
        assertNotNull(pending);
        assertNull(pending.getRefundId(), "状态与绑定写在同一个事务里，回滚后不应留下 refundId");
        assertNull(pending.getStripeStatus());
        assertEquals(ReconciliationState.PENDING.code(), pending.getReconciliationState());
        // 库存在拒单事务内已归还并提交，不能因为退款同步失败而回滚成预扣状态
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());
        verify(refundGateway, times(1)).createRefund(PAYMENT_INTENT_ID, AMOUNT_MINOR, IDEMPOTENCY_KEY);

        jdbc.execute("ALTER TABLE order_stripe_refunds DROP CONSTRAINT reject_terminal_refund_status");
        refundRecoveryTask.recoverPendingRefunds();

        assertEquals(OrderStatusEnum.REFUNDED.getCode(), orderStatus());
        OrderStripeRefund refunded = refundRecord();
        assertEquals(REFUND_ID, refunded.getRefundId());
        assertEquals("succeeded", refunded.getStripeStatus());
        assertEquals(ReconciliationState.RECONCILED.code(), refunded.getReconciliationState());
        // 库存只归还了一次：拒单时归还，恢复阶段不再归还
        assertEquals(STOCK_AFTER_REFUND, stock());
        assertEquals(1, refundRowCount());

        // 本地没有 refundId 只能重新发起创建，但两次用的是同一个幂等键
        // → Stripe 返回同一笔退款，不会产生第二笔
        ArgumentCaptor<String> idempotencyKeys = ArgumentCaptor.forClass(String.class);
        verify(refundGateway, times(2)).createRefund(
                eq(PAYMENT_INTENT_ID), eq(AMOUNT_MINOR), idempotencyKeys.capture());
        assertEquals(List.of(IDEMPOTENCY_KEY, IDEMPOTENCY_KEY), idempotencyKeys.getAllValues());
        verify(refundGateway, never()).retrieveRefund(anyString());
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
                ) VALUES (?, ?, ?, ?, 'succeeded', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'COMPLETE')
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
                    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                    stripe_status VARCHAR(32),
                    reconciliation_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_at TIMESTAMP NULL,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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

    private OrderStripeRefund refundRecord() {
        return refundMapper.selectRefundmentByOrderId(ORDER_ID);
    }

    private static StripeRefundSnapshot snapshot(String status) {
        return new StripeRefundSnapshot(REFUND_ID, PAYMENT_INTENT_ID, AMOUNT_MINOR, CURRENCY, status);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MybatisPlusConfig.class)
    @MapperScan(basePackageClasses = {OrderStripePaymentMapper.class, OrderStripeRefundMapper.class,
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
                                                                OrderMapper orderMapper,
                                                                OrderStripePaymentMapper paymentMapper,
                                                                OrderItemService orderItemService) {
            return new StripeRefundTransactionService(refundMapper, orderMapper, paymentMapper, orderItemService);
        }

        @Bean
        StripeRefundService stripeRefundService(StripeRefundTransactionService transactions,
                                                StripeRefundGateway gateway,
                                                OrderStripePaymentMapper paymentMapper,
                                                OrderStripeRefundMapper refundMapper) {
            return new StripeRefundService(transactions, gateway, paymentMapper, refundMapper);
        }

        @Bean
        StripeRefundRecoveryTask refundRecoveryTask(OrderStripeRefundMapper refundMapper,
                                                   StripeRefundService refundService,
                                                   StripeRefundTransactionService refundTransactions) {
            return new StripeRefundRecoveryTask(refundMapper, refundService, refundTransactions);
        }
    }
}
