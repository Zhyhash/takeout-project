package org.example.takeout.Payment.Gateway;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** 将服务端支付操作和 Webhook 事件解析适配到 Stripe Java SDK，并只发送服务端提供的人民币金额。 */
@Slf4j
public final class StripePaymentGatewayImpl implements StripePaymentGateway {
    public static final String CURRENCY = "cny";

    private static final String ORDER_REFERENCE_METADATA_KEY = "order_reference";

    private final StripeClient stripeClient;
    private final String webhookSecret;

    public StripePaymentGatewayImpl(StripeClient stripeClient, String webhookSecret) {
        this.stripeClient = stripeClient;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public StripePaymentSnapshot createPaymentIntent(long amountMinor, String orderReference, String idempotencyKey) {
        if (amountMinor < 1) {
            throw new IllegalArgumentException("Stripe amount must be positive CNY minor units");
        }
        if (orderReference == null || orderReference.isBlank()) {
            throw new IllegalArgumentException("Order reference is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Stripe idempotency key is required");
        }

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountMinor)
                .setCurrency(CURRENCY)
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                .putMetadata(ORDER_REFERENCE_METADATA_KEY, orderReference)
                .build();

        try {
            PaymentIntent intent = stripeClient.v1().paymentIntents().create(params,
                    buildIdempotentRequestOptions(idempotencyKey));

            return mapPaymentIntentToSnapshot(intent, true);
        } catch (StripeException exception) {
            // 创建失败同样分两种：Stripe 明确拒绝（确定没建成）与超时/5xx（可能已建成）。
            throw StripeGatewayException.fromStripeFailure(exception);
        }
    }

    @Override
    public StripePaymentSnapshot retrievePaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null || !paymentIntentId.startsWith("pi_")) {
            throw new IllegalArgumentException("A PaymentIntent ID is required");
        }

        try {
            PaymentIntent intent = stripeClient.v1().paymentIntents().retrieve(paymentIntentId);

            return mapPaymentIntentToSnapshot(intent, true);
        } catch (StripeException exception) {
            throw StripeGatewayException.fromStripeFailure(exception);
        }
    }

    @Override
    public StripePaymentSnapshot cancelPaymentIntent(String paymentIntentId, String idempotencyKey) {
        if (paymentIntentId == null || !paymentIntentId.startsWith("pi_")
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("PaymentIntent ID and cancellation idempotency key are required");
        }
        try {
            PaymentIntent intent = stripeClient.v1().paymentIntents().cancel(paymentIntentId,
                    PaymentIntentCancelParams.builder()
                            .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED).build(),
                    buildIdempotentRequestOptions(idempotencyKey));
            return mapPaymentIntentToSnapshot(intent, false);
        } catch (StripeException exception) {
            StripeGatewayException failure = StripeGatewayException.fromStripeFailure(exception);
            if (failure.getKind() == StripeGatewayException.Kind.REQUEST_REJECTED) {
                // 最常见的原因是用户恰好在同一刻完成了支付，或订单已被取消：
                // 远端已不可取消，这是确定的业务结论，不是网络故障，单独记一条便于排查。
                log.warn("Stripe refused cancellation: intentId={}, httpStatus={}, code={}",
                        paymentIntentId, failure.getHttpStatus(), failure.getStripeErrorCode());
            }
            throw failure;
        }
    }

    @Override
    public StripePaymentEvent verifySignatureAndParsePaymentEvent(String rawBody, String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        final Event event;
        try {
            event = stripeClient.constructEvent(rawBody, signature, webhookSecret);
        } catch (Exception exception) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        Boolean eventLivemode = event.getLivemode();
        if (event.getId() == null || event.getType() == null || eventLivemode == null) {
            throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
        }

        StripePaymentSnapshot payment = null;
        if (event.getType() != null && event.getType().startsWith("payment_intent.")) {
            String eventApiVersion = event.getApiVersion();
            if (eventApiVersion == null || eventApiVersion.isBlank()) {
                throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
            }

            final StripeObject stripeObject;
            try {
                var deserializer = event.getDataObjectDeserializer();
                if (deserializer == null) {
                    throw new IllegalStateException("Stripe event data is missing");
                }

                StripeObject deserializedObject = deserializer.getObject().orElse(null);
                if (deserializedObject == null) {
                    stripeObject = deserializer.deserializeUnsafe();
                } else {
                    stripeObject = deserializedObject;
                }
            } catch (com.stripe.exception.EventDataObjectDeserializationException | RuntimeException exception) {
                throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
            }

            if (stripeObject instanceof PaymentIntent intent) {
                payment = mapPaymentIntentToSnapshot(intent, false, StripeGatewayException.Kind.INVALID_WEBHOOK);
            } else {
                throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
            }
        }

        return new StripePaymentEvent(event.getId(), event.getType(), eventLivemode, payment);
    }

    private RequestOptions buildIdempotentRequestOptions(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private StripePaymentSnapshot mapPaymentIntentToSnapshot(PaymentIntent intent, boolean includeClientSecret) {
        // 响应缺少 livemode 说明返回不可信，按"结果未知"处理，交由对账核实真实状态。
        return mapPaymentIntentToSnapshot(intent, includeClientSecret, StripeGatewayException.Kind.OUTCOME_UNKNOWN);
    }

    private StripePaymentSnapshot mapPaymentIntentToSnapshot(PaymentIntent intent, boolean includeClientSecret,
                                                              StripeGatewayException.Kind missingLivemodeKind) {
        if (intent == null || intent.getLivemode() == null) {
            throw new StripeGatewayException(missingLivemodeKind);
        }

        Map<String, String> metadata = intent.getMetadata();

        return new StripePaymentSnapshot(
                intent.getId(),
                intent.getAmount(),
                intent.getAmountReceived(),
                intent.getCurrency(),
                intent.getStatus(),
                intent.getLivemode(),
                metadata == null ? null : metadata.get(ORDER_REFERENCE_METADATA_KEY),
                includeClientSecret ? intent.getClientSecret() : null
        );
    }
}
