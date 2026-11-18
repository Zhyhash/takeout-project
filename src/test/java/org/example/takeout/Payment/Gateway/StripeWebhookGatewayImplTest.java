package org.example.takeout.Payment.Gateway;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeWebhookGatewayImplTest {
    private static final String WEBHOOK_SECRET = "whsec_placeholder";

    private final StripeWebhookGateway gateway = new StripeWebhookGatewayImpl(
            new StripeClient("rk_test_placeholder"), WEBHOOK_SECRET);

    @Test
    void verifySignatureAndParseEventRejectsMalformedSignatureWithSafeTypedError() {
        assertThatThrownBy(() -> gateway.verifySignatureAndParseEvent("{}", "invalid-signature"))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> {
                    StripeGatewayException gatewayError = (StripeGatewayException) error;
                    assertThat(gatewayError.getKind()).isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK);
                    assertThat(gatewayError.getMessage()).doesNotContain(WEBHOOK_SECRET);
                });
    }

    @Test
    void verifySignatureAndParseEventRejectsSignedWebhookWithMissingLivemode() throws Exception {
        String rawBody = """
                {"id":"evt_test","object":"event","created":1700000000,
                 "type":"payment_intent.succeeded",
                 "data":{"object":{"id":"pi_test","object":"payment_intent"}}}
                """;
        String signature = signPayload(rawBody, WEBHOOK_SECRET);

        assertThatThrownBy(() -> gateway.verifySignatureAndParseEvent(rawBody, signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void verifySignatureAndParseEventRejectsPaymentIntentWebhookWithMissingApiVersion() throws Exception {
        String rawBody = """
                {"id":"evt_test_missing_api_version","object":"event","created":1700000000,
                 "type":"payment_intent.succeeded","livemode":false,
                 "data":{"object":{"id":"pi_test_missing_api_version","object":"payment_intent",
                 "amount":12345,"amount_received":12345,"currency":"cny","livemode":false,
                 "metadata":{"order_reference":"ORD-2026-123"},"status":"succeeded"}}}
                """;
        String signature = signPayload(rawBody, WEBHOOK_SECRET);

        assertThatThrownBy(() -> gateway.verifySignatureAndParseEvent(rawBody, signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void verifySignatureAndParseEventRejectsPaymentIntentWithMissingLivemode() throws Exception {
        String rawBody = """
                {"id":"evt_test_pi_missing_livemode","object":"event","api_version":"%s","created":1700000000,
                 "type":"payment_intent.succeeded","livemode":false,
                 "data":{"object":{"id":"pi_test_missing_livemode","object":"payment_intent",
                 "amount":12345,"amount_received":12345,"currency":"cny",
                 "metadata":{"order_reference":"ORD-2026-123"},"status":"succeeded"}}}
                """.formatted(Stripe.API_VERSION);
        String signature = signPayload(rawBody, WEBHOOK_SECRET);

        assertThatThrownBy(() -> gateway.verifySignatureAndParseEvent(rawBody, signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void verifySignatureAndParseEventMapsVerifiedPaymentWithoutClientSecret() throws Exception {
        String rawBody = """
                {"id":"evt_test_123","object":"event","api_version":"%s",
                 "created":1700000000,"data":{"object":{"id":"pi_test_123",
                 "object":"payment_intent","amount":12345,"amount_received":12345,
                 "currency":"cny","livemode":false,"metadata":{"order_reference":"ORD-2026-123"},
                 "status":"succeeded","client_secret":"pi_test_123_secret_do_not_return"}},
                 "livemode":false,"pending_webhooks":1,"type":"payment_intent.succeeded"}
                """.formatted(Stripe.API_VERSION);
        String signature = signPayload(rawBody, WEBHOOK_SECRET);

        StripeWebhookEvent event = gateway.verifySignatureAndParseEvent(rawBody, signature);

        assertThat(event.id()).isEqualTo("evt_test_123");
        assertThat(event.type()).isEqualTo("payment_intent.succeeded");
        assertThat(event.livemode()).isFalse();
        assertThat(event.refund()).isNull();
        assertThat(event.payment()).satisfies(payment -> {
            assertThat(payment.id()).isEqualTo("pi_test_123");
            assertThat(payment.amount()).isEqualTo(12345L);
            assertThat(payment.amountReceived()).isEqualTo(12345L);
            assertThat(payment.currency()).isEqualTo("cny");
            assertThat(payment.status()).isEqualTo("succeeded");
            assertThat(payment.orderReference()).isEqualTo("ORD-2026-123");
            assertThat(payment.livemode()).isFalse();
            assertThat(payment.clientSecret()).isNull();
            assertThat(payment.toString()).doesNotContain("pi_test_123_secret_do_not_return");
        });

        assertThatThrownBy(() -> gateway.verifySignatureAndParseEvent(
                rawBody.replace("12345", "12346"), signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void verifySignatureAndParseRefundEventMapsRefundWithoutPaymentSnapshot() throws Exception {
        String rawBody = """
                {"id":"evt_refund_123","object":"event","api_version":"%s","created":1700000000,
                 "type":"refund.created","livemode":false,
                 "data":{"object":{"id":"re_test_123","object":"refund","amount":500,"currency":"cny",
                 "payment_intent":"pi_test_123","metadata":{"refund_attempt_key":"refund_attempt_test"},"status":"succeeded"}}}
                """.formatted(Stripe.API_VERSION);
        String signature = signPayload(rawBody, WEBHOOK_SECRET);

        StripeWebhookEvent event = gateway.verifySignatureAndParseEvent(rawBody, signature);

        assertThat(event.type()).isEqualTo("refund.created");
        assertThat(event.payment()).isNull();
        assertThat(event.refund()).satisfies(refund -> {
            assertThat(refund.refundId()).isEqualTo("re_test_123");
            assertThat(refund.paymentIntentId()).isEqualTo("pi_test_123");
            assertThat(refund.amountMinor()).isEqualTo(500L);
            assertThat(refund.currency()).isEqualTo("cny");
            assertThat(refund.status()).isEqualTo("succeeded");
            assertThat(refund.attemptKey()).isEqualTo("refund_attempt_test");
        });
    }

    @Test
    void legacyRefundOnlyUsesTheIdempotencyKeyOfTheCreationRequest() throws Exception {
        String rawBody = """
                {"id":"evt_legacy","object":"event","api_version":"%s","livemode":false,"type":"refund.created",
                 "request":{"id":"req_legacy","idempotency_key":"order_refund_42"},
                 "data":{"object":{"id":"re_legacy","object":"refund","amount":500,"currency":"cny",
                 "payment_intent":"pi_42","status":"pending"}}}
                """.formatted(Stripe.API_VERSION);
        assertThat(gateway.verifySignatureAndParseEvent(rawBody, signPayload(rawBody, WEBHOOK_SECRET))
                .refund().attemptKey()).isEqualTo("order_refund_42");
        String update = rawBody.replace("refund.created", "refund.updated");
        assertThat(gateway.verifySignatureAndParseEvent(update, signPayload(update, WEBHOOK_SECRET))
                .refund().attemptKey()).isNull();
    }

    @Test
    void verifySignatureAndParseRefundEventRejectsPayloadWithoutAmount() throws Exception {
        String rawBody = """
                {"id":"evt_refund_incomplete","object":"event","api_version":"%s","created":1700000000,
                 "type":"refund.updated","livemode":false,
                 "data":{"object":{"id":"re_test_incomplete","object":"refund","currency":"cny",
                 "payment_intent":"pi_test_123","status":"succeeded"}}}
                """.formatted(Stripe.API_VERSION);
        String signature = signPayload(rawBody, WEBHOOK_SECRET);

        assertThatThrownBy(() -> gateway.verifySignatureAndParseEvent(rawBody, signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    private static String signPayload(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signature);
    }
}
