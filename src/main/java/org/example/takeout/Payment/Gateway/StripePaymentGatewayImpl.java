package org.example.takeout.Payment.Gateway;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

import java.util.Map;

/** Stripe Java SDK adapter. Only sends CNY amounts supplied by the server-side order service. */
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
    public StripePaymentSnapshot create(long amountMinor, String orderReference, String idempotencyKey) {
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
            PaymentIntent intent = stripeClient.v1().paymentIntents().create(params, requestOptions(idempotencyKey));
            return snapshot(intent, true);
        } catch (StripeException exception) {
            throw new StripeGatewayException(StripeGatewayException.Kind.REQUEST_FAILED);
        }
    }

    @Override
    public StripePaymentSnapshot retrieve(String paymentIntentId) {
        if (paymentIntentId == null || !paymentIntentId.startsWith("pi_")) {
            throw new IllegalArgumentException("A PaymentIntent ID is required");
        }
        try {
            PaymentIntent intent = stripeClient.v1().paymentIntents().retrieve(paymentIntentId);
            return snapshot(intent, true);
        } catch (StripeException exception) {
            throw new StripeGatewayException(StripeGatewayException.Kind.REQUEST_FAILED);
        }
    }

    @Override
    public StripePaymentEvent verifyEvent(String rawBody, String signature) {
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
                payment = snapshot(intent, false, StripeGatewayException.Kind.INVALID_WEBHOOK);
            } else {
                throw new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK);
            }
        }
        return new StripePaymentEvent(event.getId(), event.getType(), eventLivemode, payment);
    }

    private RequestOptions requestOptions(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private StripePaymentSnapshot snapshot(PaymentIntent intent, boolean includeClientSecret) {
        return snapshot(intent, includeClientSecret, StripeGatewayException.Kind.REQUEST_FAILED);
    }

    private StripePaymentSnapshot snapshot(PaymentIntent intent, boolean includeClientSecret,
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
