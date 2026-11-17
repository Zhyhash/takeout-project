package org.example.takeout.Payment.Service;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Payment.Gateway.StripePaymentSnapshot;
import org.example.takeout.Payment.Gateway.StripeRefundSnapshot;
import org.example.takeout.Payment.Gateway.StripeWebhookEvent;
import org.example.takeout.Payment.Gateway.StripeWebhookGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Webhook 编排层的职责：验签解析 → 环境裁决 → 按类型分发，不承担任何业务写入。 */
class StripeWebhookServiceTest {
    private final StripeWebhookGateway webhookGateway = mock(StripeWebhookGateway.class);
    private final StripePaymentService paymentService = mock(StripePaymentService.class);
    private final StripeRefundService refundService = mock(StripeRefundService.class);
    private final StripeWebhookService service =
            new StripeWebhookService(webhookGateway, paymentService, refundService);

    @Test
    void paymentEventIsDispatchedToThePaymentService() {
        StripeWebhookEvent event = new StripeWebhookEvent("evt_pay", "payment_intent.succeeded",
                false, paymentSnapshot(), null);
        when(webhookGateway.verifySignatureAndParseEvent("raw", "signature")).thenReturn(event);

        service.processWebhookEvent("raw", "signature");

        verify(webhookGateway).verifySignatureAndParseEvent("raw", "signature");
        verify(paymentService).processWebhookPaymentEvent(event);
        verifyNoInteractions(refundService);
    }

    @Test
    void refundEventIsDispatchedToTheRefundService() {
        StripeWebhookEvent event = new StripeWebhookEvent("evt_refund", "refund.updated",
                false, null, refundSnapshot());
        when(webhookGateway.verifySignatureAndParseEvent("raw", "signature")).thenReturn(event);

        service.processWebhookEvent("raw", "signature");

        verify(refundService).processWebhookRefundEvent(event);
        verifyNoInteractions(paymentService);
    }

    @Test
    void liveModeEventIsRejectedBeforeAnyBusinessWrite() {
        StripeWebhookEvent event = new StripeWebhookEvent("evt_live", "payment_intent.succeeded",
                true, paymentSnapshot(), null);
        when(webhookGateway.verifySignatureAndParseEvent("raw", "signature")).thenReturn(event);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.processWebhookEvent("raw", "signature"));

        assertEquals(ResultCodeEnum.PARAM_ERROR, failure.getCodeEnum());
        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void unsupportedEventTypeOrMissingBusinessObjectIsIgnored() {
        StripeWebhookEvent chargeEvent = new StripeWebhookEvent("evt_charge", "charge.succeeded",
                false, null, null);
        StripeWebhookEvent refundWithoutObject = new StripeWebhookEvent("evt_refund_empty",
                "refund.updated", false, null, null);
        when(webhookGateway.verifySignatureAndParseEvent("raw", "signature"))
                .thenReturn(chargeEvent, refundWithoutObject);

        service.processWebhookEvent("raw", "signature");
        service.processWebhookEvent("raw", "signature");

        verifyNoInteractions(paymentService, refundService);
    }

    private static StripePaymentSnapshot paymentSnapshot() {
        return new StripePaymentSnapshot("pi_test", 12_345L, 12_345L, "cny", "succeeded",
                false, "701", null);
    }

    private static StripeRefundSnapshot refundSnapshot() {
        return new StripeRefundSnapshot("re_test", "pi_test", 12_345L, "cny", "succeeded", null);
    }
}
