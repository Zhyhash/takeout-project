package org.example.takeout.Payment.Gateway;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StripePaymentGatewayImplTest {
    private final StripePaymentGatewayImpl gateway = new StripePaymentGatewayImpl(
            new StripeClient("rk_test_placeholder"), "whsec_placeholder");

    @Test
    void cancellationUsesTheSpecificIntentAndStableKeyAndNeverReturnsClientSecret() throws Exception {
        StripeClient client = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        PaymentIntent canceled = validPaymentIntent("pi_abandoned", 12345L, "501");
        canceled.setStatus("canceled");
        canceled.setAmountReceived(0L);
        canceled.setClientSecret("do_not_return");
        when(client.v1().paymentIntents().cancel(org.mockito.ArgumentMatchers.eq("pi_abandoned"),
                any(PaymentIntentCancelParams.class), any(RequestOptions.class))).thenReturn(canceled);
        StripePaymentSnapshot result = new StripePaymentGatewayImpl(client, "whsec_placeholder")
                .cancelPaymentIntent("pi_abandoned", "cancel-original-key");
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.v1().paymentIntents()).cancel(org.mockito.ArgumentMatchers.eq("pi_abandoned"),
                any(PaymentIntentCancelParams.class), options.capture());
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("cancel-original-key");
        assertThat(result.status()).isEqualTo("canceled");
        assertThat(result.clientSecret()).isNull();
    }

    @Test
    void paymentAmountsUseCny() {
        assertThat(StripePaymentGatewayImpl.CURRENCY).isEqualTo("cny");
    }

    @Test
    void cancellationRejectedByStripeIsADeterminedFailureThatKeepsTheSdkCause() throws Exception {
        StripeClient client = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        InvalidRequestException rejected = new InvalidRequestException(
                "This PaymentIntent's status does not allow cancellation.",
                null, null, "payment_intent_unexpected_state", 400, null);
        when(client.v1().paymentIntents().cancel(anyString(), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(rejected);

        StripePaymentGatewayImpl refusingGateway = new StripePaymentGatewayImpl(client, "whsec_placeholder");

        assertThatThrownBy(() -> refusingGateway.cancelPaymentIntent("pi_already_paid", "cancel-refused"))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> {
                    StripeGatewayException gatewayError = (StripeGatewayException) error;
                    assertThat(gatewayError.getKind()).isEqualTo(StripeGatewayException.Kind.REQUEST_REJECTED);
                    assertThat(gatewayError.getStripeErrorCode()).isEqualTo("payment_intent_unexpected_state");
                    assertThat(gatewayError.getHttpStatus()).isEqualTo(400);
                    assertThat(gatewayError.isOutcomeUnknown()).isFalse();
                    // 原始 SDK 异常必须保留，否则堆栈到网关这一层就断了。
                    assertThat(gatewayError.getCause()).isSameAs(rejected);
                });
    }

    @Test
    void cancellationTimeoutIsAnUnknownOutcomeThatKeepsTheSdkCause() throws Exception {
        StripeClient client = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        ApiConnectionException timeout = new ApiConnectionException("Read timed out");
        when(client.v1().paymentIntents().cancel(anyString(), any(PaymentIntentCancelParams.class),
                any(RequestOptions.class))).thenThrow(timeout);

        StripePaymentGatewayImpl timingOutGateway = new StripePaymentGatewayImpl(client, "whsec_placeholder");

        assertThatThrownBy(() -> timingOutGateway.cancelPaymentIntent("pi_pending", "cancel-timeout"))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> {
                    StripeGatewayException gatewayError = (StripeGatewayException) error;
                    assertThat(gatewayError.getKind()).isEqualTo(StripeGatewayException.Kind.OUTCOME_UNKNOWN);
                    assertThat(gatewayError.isOutcomeUnknown()).isTrue();
                    assertThat(gatewayError.getHttpStatus()).isNull();
                    assertThat(gatewayError.getCause()).isSameAs(timeout);
                });
    }

    @Test
    void createPaymentIntentRejectsInvalidServerAmountAndMissingIdempotencyBeforeNetworkCall() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> gateway.createPaymentIntent(0, "ORD123", "attempt-1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> gateway.createPaymentIntent(-1, "ORD123", "attempt-1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> gateway.createPaymentIntent(100, "ORD123", " "));
    }

    @Test
    void createPaymentIntentSendsCnyMinorAmountOrderReferenceAndIdempotencyWithoutNetwork() throws Exception {
        PaymentIntent paymentIntent = validPaymentIntent("pi_created", 12345L, "ORD-2026-123");
        paymentIntent.setAmountReceived(0L);
        paymentIntent.setStatus("requires_payment_method");
        paymentIntent.setClientSecret("pi_created_secret_test");

        StripeClient responseClient = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        when(responseClient.v1().paymentIntents().create(
                any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(paymentIntent);
        StripePaymentGateway responseGateway = new StripePaymentGatewayImpl(
                responseClient, "whsec_placeholder");

        StripePaymentSnapshot snapshot = responseGateway.createPaymentIntent(
                12345L, "ORD-2026-123", "order-ORD-2026-123-attempt-1");

        ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> optionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(responseClient.v1().paymentIntents()).create(paramsCaptor.capture(), optionsCaptor.capture());
        PaymentIntentCreateParams params = paramsCaptor.getValue();

        assertThat(params.getAmount()).isEqualTo(12345L);
        assertThat(params.getCurrency()).isEqualTo("cny");
        assertThat(params.getMetadata()).containsEntry("order_reference", "ORD-2026-123");
        assertThat(optionsCaptor.getValue().getIdempotencyKey())
                .isEqualTo("order-ORD-2026-123-attempt-1");
        assertThat(snapshot.id()).isEqualTo("pi_created");
        assertThat(snapshot.clientSecret()).isEqualTo("pi_created_secret_test");
    }

    @Test
    void verifiedEventObjectsNeverCarryClientSecretsInSnapshotLogs() {
        StripePaymentSnapshot snapshot = new StripePaymentSnapshot(
                "pi_example", 1250L, 0L, "cny", "requires_payment_method", false,
                "ORD123", "pi_example_secret_must_not_be_logged");

        assertThat(snapshot.toString())
                .contains("clientSecret=[REDACTED]")
                .doesNotContain("pi_example_secret_must_not_be_logged");
    }

    @Test
    void verifySignatureAndParsePaymentEventRejectsMalformedSignatureWithSafeTypedError() {
        assertThatThrownBy(() -> gateway.verifySignatureAndParsePaymentEvent("{}", "invalid-signature"))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> {
                    StripeGatewayException gatewayError = (StripeGatewayException) error;
                    assertThat(gatewayError.getKind()).isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK);
                    assertThat(gatewayError.getMessage()).doesNotContain("whsec_placeholder");
                });
    }

    @Test
    void verifySignatureAndParsePaymentEventRejectsSignedWebhookWithMissingLivemode() throws Exception {
        String rawBody = """
                {"id":"evt_test","object":"event","created":1700000000,
                 "type":"payment_intent.succeeded",
                 "data":{"object":{"id":"pi_test","object":"payment_intent"}}}
                """;
        String signature = signPayload(rawBody, "whsec_placeholder");

        assertThatThrownBy(() -> gateway.verifySignatureAndParsePaymentEvent(rawBody, signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void verifySignatureAndParsePaymentEventRejectsPaymentIntentWebhookWithMissingApiVersion() throws Exception {
        String rawBody = """
                {"id":"evt_test_missing_api_version","object":"event","created":1700000000,
                 "type":"payment_intent.succeeded","livemode":false,
                 "data":{"object":{"id":"pi_test_missing_api_version","object":"payment_intent",
                 "amount":12345,"amount_received":12345,"currency":"cny","livemode":false,
                 "metadata":{"order_reference":"ORD-2026-123"},"status":"succeeded"}}}
                """;
        String signature = signPayload(rawBody, "whsec_placeholder");

        assertThatThrownBy(() -> gateway.verifySignatureAndParsePaymentEvent(rawBody, signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void verifySignatureAndParsePaymentEventRejectsPaymentIntentWithMissingLivemode() throws Exception {
        String rawBody = """
                {"id":"evt_test_pi_missing_livemode","object":"event","api_version":"%s","created":1700000000,
                 "type":"payment_intent.succeeded","livemode":false,
                 "data":{"object":{"id":"pi_test_missing_livemode","object":"payment_intent",
                 "amount":12345,"amount_received":12345,"currency":"cny",
                 "metadata":{"order_reference":"ORD-2026-123"},"status":"succeeded"}}}
                """.formatted(Stripe.API_VERSION);
        String signature = signPayload(rawBody, "whsec_placeholder");

        assertThatThrownBy(() -> gateway.verifySignatureAndParsePaymentEvent(rawBody, signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void verifySignatureAndParsePaymentEventMapsVerifiedPaymentWithoutClientSecret() throws Exception {
        String rawBody = """
                {"id":"evt_test_123","object":"event","api_version":"%s",
                 "created":1700000000,"data":{"object":{"id":"pi_test_123",
                 "object":"payment_intent","amount":12345,"amount_received":12345,
                 "currency":"cny","livemode":false,"metadata":{"order_reference":"ORD-2026-123"},
                 "status":"succeeded","client_secret":"pi_test_123_secret_do_not_return"}},
                 "livemode":false,"pending_webhooks":1,"type":"payment_intent.succeeded"}
                """.formatted(Stripe.API_VERSION);
        String signature = signPayload(rawBody, "whsec_placeholder");

        StripePaymentEvent event = gateway.verifySignatureAndParsePaymentEvent(rawBody, signature);

        assertThat(event.id()).isEqualTo("evt_test_123");
        assertThat(event.type()).isEqualTo("payment_intent.succeeded");
        assertThat(event.livemode()).isFalse();
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

        assertThatThrownBy(() -> gateway.verifySignatureAndParsePaymentEvent(
                rawBody.replace("12345", "12346"), signature))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.INVALID_WEBHOOK));
    }

    @Test
    void retrievePaymentIntentRejectsMissingLivemodeAndPreservesTrueFromSdkResponse() throws Exception {
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setId("pi_test_123");
        paymentIntent.setAmount(1250L);
        paymentIntent.setAmountReceived(0L);
        paymentIntent.setCurrency("cny");
        paymentIntent.setStatus("requires_payment_method");
        paymentIntent.setMetadata(Map.of("order_reference", "123"));
        paymentIntent.setLivemode(null);

        StripeClient responseClient = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        when(responseClient.v1().paymentIntents().retrieve("pi_test_123"))
                .thenReturn(paymentIntent);

        StripePaymentGateway responseGateway = new StripePaymentGatewayImpl(
                responseClient, "whsec_placeholder");
        assertThatThrownBy(() -> responseGateway.retrievePaymentIntent("pi_test_123"))
                .isInstanceOf(StripeGatewayException.class)
                .satisfies(error -> assertThat(((StripeGatewayException) error).getKind())
                        .isEqualTo(StripeGatewayException.Kind.OUTCOME_UNKNOWN));
        verify(responseClient.v1().paymentIntents()).retrieve("pi_test_123");

        PaymentIntent liveModeIntent = validPaymentIntent("pi_test_live_mode", 1250L, "123");
        liveModeIntent.setLivemode(true);
        StripeClient liveModeClient = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        when(liveModeClient.v1().paymentIntents().retrieve("pi_test_live_mode"))
                .thenReturn(liveModeIntent);
        StripePaymentGateway liveModeGateway = new StripePaymentGatewayImpl(
                liveModeClient, "whsec_placeholder");

        assertThat(liveModeGateway.retrievePaymentIntent("pi_test_live_mode").livemode()).isTrue();
        verify(liveModeClient.v1().paymentIntents()).retrieve("pi_test_live_mode");
    }

    private static PaymentIntent validPaymentIntent(String id, Long amount, String orderReference) {
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setId(id);
        paymentIntent.setAmount(amount);
        paymentIntent.setAmountReceived(0L);
        paymentIntent.setCurrency("cny");
        paymentIntent.setStatus("requires_payment_method");
        paymentIntent.setMetadata(Map.of("order_reference", orderReference));
        paymentIntent.setLivemode(false);
        return paymentIntent;
    }

    private static String signPayload(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signature);
    }
}
