package org.example.takeout.Payment.Domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 支付模块的策略常量与时间规则集中地。
 * <p>这些数字原本散落在两个服务里：30 分钟支付窗口出现在 4 处，退避基数与封顶出现在
 * {@code retryDelaySeconds} 与恢复任务两处，幂等 23 小时有效期藏在注释里。
 * 集中之后，调整任何一个时间参数都不需要全局搜索，也不会出现"改了一半"的半衰状态。</p>
 */
public final class PaymentPolicy {

    /** 订单创建后可支付的时间窗口；超时由 Stripe 侧取消并回滚订单。 */
    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(30);

    /** Stripe 幂等键实际保留期（官方至少 24 小时），本地保守按 23 小时计。 */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(23);

    /**
     * 单次尝试的最小占位时长。
     * <p>去掉独立的租约列之后，{@code next_retry_at} 同时承担两个职责：表示"这笔记录正在被处理"，
     * 以及表示"何时可以再试一次"。因此它必须至少覆盖一次网关调用的最坏耗时
     * （连接 5 秒 + 读取 10 秒 + 一次网络重试），否则第二个 worker 会在第一个还没返回时就重新领取。</p>
     */
    public static final Duration ATTEMPT_HOLD = Duration.ofSeconds(60);

    /** 判断幂等键"仍可用于重试"时预留的安全余量。 */
    public static final Duration IDEMPOTENCY_SAFETY_MARGIN = Duration.ofMinutes(2);

    /** 单笔支付允许的最大恢复次数，超过后转人工复核。 */
    public static final int MAX_RECOVERY_ATTEMPTS = 96;

    /** 指数退避基数（秒）。 */
    public static final long BASE_RETRY_SECONDS = 15L;

    /** 指数退避上限（秒）。 */
    public static final long MAX_RETRY_SECONDS = 1_800L;

    /** 指数退避最大幂次，防止位移溢出。 */
    public static final int MAX_BACKOFF_EXPONENT = 7;

    /** Stripe PaymentIntent ID 前缀，用于快速拒绝明显非法的入参。 */
    public static final String PAYMENT_INTENT_PREFIX = "pi_";

    /** 恢复任务单批处理上限。 */
    public static final int MAX_RECOVERY_BATCH = 200;

    private PaymentPolicy() {
    }

    /**
     * 订单是否仍在支付窗口内。
     *
     * @param createdAt 订单创建时间
     * @param now       当前时间（应使用数据库时间，避免 JVM 与库时钟漂移）
     */
    public static boolean isWithinPaymentWindow(LocalDateTime createdAt, LocalDateTime now) {
        return createdAt != null && createdAt.isAfter(now.minus(PAYMENT_WINDOW));
    }

    /** 支付窗口是否已过期；创建时间为空的历史脏数据按已过期处理，避免继续发起支付。 */
    public static boolean isPaymentWindowExpired(LocalDateTime createdAt, LocalDateTime now) {
        return !isWithinPaymentWindow(createdAt, now);
    }

    /**
     * 第 N 次失败后的等待秒数：15、30、60……封顶 1800 秒。
     *
     * @param attempts 已经失败的次数，允许为空（视为 0 次）
     */
    public static long retryDelaySeconds(Integer attempts) {
        int exponent = Math.min(MAX_BACKOFF_EXPONENT, Math.max(0, (attempts == null ? 0 : attempts) - 1));
        return Math.min(MAX_RETRY_SECONDS, BASE_RETRY_SECONDS << exponent);
    }

    /**
     * 本次尝试应占用的时长：退避间隔与最小占位时长的较大者。
     * <p>早期取占位时长（保证网关调用期间不被重复领取），后期由指数退避主导（降低重试频率）。</p>
     */
    public static Duration attemptHold(Integer attempts) {
        Duration backoff = Duration.ofSeconds(retryDelaySeconds(attempts));
        return backoff.compareTo(ATTEMPT_HOLD) > 0 ? backoff : ATTEMPT_HOLD;
    }
}
