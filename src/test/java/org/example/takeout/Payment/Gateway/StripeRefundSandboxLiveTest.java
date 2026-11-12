package org.example.takeout.Payment.Gateway;

import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第一轮「Stripe 测试后台确认退款对象存在」的真实沙箱版。
 *
 * <p>默认不执行：只有显式提供受限测试密钥（{@code rk_test_}）时才会跑，避免 CI
 * 依赖外网与账户状态。运行方式：</p>
 * <pre>
 * STRIPE_SECRET_KEY=rk_test_xxx ./mvnw -o -B -Dtest=StripeRefundSandboxLiveTest test
 * </pre>
 *
 * <p>它做三件真实的事：用 {@code pm_card_visa} 在测试模式确认一笔 PaymentIntent、
 * 通过生产同一份 {@link StripeRefundGatewayImpl} 创建退款、再用退款单号 retrieve 一次。
 * 第三步能取回同一笔退款对象，就等价于在 Stripe 测试后台看到该退款记录。</p>
 */
@Tag("stripe-live")
@EnabledIfEnvironmentVariable(named = "STRIPE_SECRET_KEY", matches = "rk_test_.+")
class StripeRefundSandboxLiveTest {

    private static final long AMOUNT_MINOR = 12_345L;
    private static final String CURRENCY = "cny";

    @Test
    void refundIsCreatedInSandboxAndCanBeRetrievedAndReplayedIdempotently() throws Exception {
        StripeClient client = StripeClient.builder()
                .setApiKey(System.getenv("STRIPE_SECRET_KEY"))
                .setMaxNetworkRetries(1)
                .build();
        StripeRefundGateway gateway = new StripeRefundGatewayImpl(client);

        PaymentIntent intent = client.v1().paymentIntents().create(PaymentIntentCreateParams.builder()
                .setAmount(AMOUNT_MINOR)
                .setCurrency(CURRENCY)
                .setPaymentMethod("pm_card_visa")
                .setConfirm(true)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build())
                .putMetadata("takeout_test", "stripe-refund-sandbox")
                .build());
        assertEquals("succeeded", intent.getStatus(), "pm_card_visa 在测试模式应立即支付成功");

        String idempotencyKey = "takeout-refund-sandbox-" + UUID.randomUUID();
        StripeRefundSnapshot created = gateway.createRefund(intent.getId(), AMOUNT_MINOR, idempotencyKey);

        assertNotNull(created.refundId());
        assertTrue(Set.of("succeeded", "pending").contains(created.status()),
                "银行卡退款应直接 succeeded 或短暂 pending，实际=" + created.status());
        assertEquals(intent.getId(), created.paymentIntentId());
        assertEquals(AMOUNT_MINOR, created.amountMinor());
        assertEquals(CURRENCY, created.currency());

        // 对应「在 Stripe 测试后台确认退款对象存在」：按退款单号回查得到同一对象
        StripeRefundSnapshot retrieved = gateway.retrieveRefund(created.refundId());
        assertEquals(created.refundId(), retrieved.refundId());
        assertEquals(created.paymentIntentId(), retrieved.paymentIntentId());
        assertEquals(created.amountMinor(), retrieved.amountMinor());
        assertEquals(created.currency(), retrieved.currency());

        // 同一幂等键重放（模拟本地没收到响应后的重试）不会在 Stripe 侧产生第二笔退款
        StripeRefundSnapshot replay = gateway.createRefund(intent.getId(), AMOUNT_MINOR, idempotencyKey);
        assertEquals(created.refundId(), replay.refundId());
    }
}
