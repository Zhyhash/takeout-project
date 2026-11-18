package org.example.takeout.Payment.Config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Payment.Entity.OrderStripeRefundAttempt;
import org.example.takeout.Payment.Mapper.OrderStripeRefundAttemptMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 退款失败巡检：定时把 {@code stripe_status = failed} 的退款记录捞出来并汇总告警。
 * <p>这里刻意只读：失败之后要不要自动重试，取决于 {@code failure_reason}（卡已过期这类原因重试也没用），
 * 属于业务判断；本任务既不推进状态，也不消耗 {@code retry_count} 预算，只保证"钱没退成"不再沉默。</p>
 * <p>与 {@link StripeRefundRecoveryTask} 分开：那个任务扫 {@code PENDING} 记录做自动对账，
 * 这个任务扫 {@code failed} 记录做人工可见性，触发条件与副作用完全不同，混在一起会让状态机难以推理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeRefundFailureScanTask {
    /** 单批上限，与恢复任务保持同一量级，避免一次巡检打满日志。 */
    private static final int MAX_SCAN = 200;
    /** Stripe 未返回原因时的占位，便于在聚合结果里直接看出缺口。 */
    private static final String UNKNOWN_REASON = "unknown";

    private final OrderStripeRefundAttemptMapper attemptMapper;

    /** 上一次告警的快照：失败集合没变化时不再重复打 warn，避免同一笔失败每 10 分钟刷一条。 */
    private volatile String lastReportedSignature;

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void scanFailedRefunds() {
        List<OrderStripeRefundAttempt> failed = attemptMapper.selectFailedAttempts(MAX_SCAN);
        if (failed.isEmpty()) {
            lastReportedSignature = null;
            return;
        }

        Map<String, Long> countByReason = failed.stream()
                .collect(Collectors.groupingBy(
                        refund -> Objects.requireNonNullElse(refund.getFailureReason(), UNKNOWN_REASON),
                        Collectors.counting()));
        List<Long> orderIds = failed.stream().map(OrderStripeRefundAttempt::getOrderId).toList();
        String signature = failed.size() + "|" + countByReason + "|" + orderIds;

        if (signature.equals(lastReportedSignature)) {
            log.debug("Stripe 退款失败集合未变化: count={}", failed.size());
            return;
        }
        lastReportedSignature = signature;

        if (failed.size() >= MAX_SCAN) {
            log.warn("Stripe 退款失败待处理: count>={}（已达单批上限，可能还有更多）, byReason={}, orderIds={}",
                    failed.size(), countByReason, orderIds);
            return;
        }
        log.warn("Stripe 退款失败待处理: count={}, byReason={}, orderIds={}",
                failed.size(), countByReason, orderIds);
    }
}
