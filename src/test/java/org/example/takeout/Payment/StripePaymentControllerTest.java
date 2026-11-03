package org.example.takeout.Payment;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Payment.Controller.StripePaymentController;
import org.example.takeout.Payment.Controller.StripePaymentExceptionHandler;
import org.example.takeout.Payment.Controller.StripeWebhookController;
import org.example.takeout.Payment.Gateway.StripeGatewayException;
import org.example.takeout.Payment.Service.StripePaymentService;
import org.example.takeout.Payment.VO.StripePaymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StripePaymentControllerTest {
    private final StripePaymentService payments = org.mockito.Mockito.mock(StripePaymentService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new StripePaymentController(payments), new StripeWebhookController(payments))
                .setControllerAdvice(new StripePaymentExceptionHandler())
                .build();
    }

    @Test
    void createUsesOnlyPathOrderIdAndDisablesCaching() throws Exception {
        when(payments.createOrReuseOrderPaymentIntent(42L)).thenReturn(view("requires_payment_method", 0, "pi_test_42"));

        mockMvc.perform(post("/order/42/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.01,\"amountMinor\":1,\"orderId\":999}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.orderId").value(42))
                .andExpect(jsonPath("$.data.clientSecret").value("pi_test_secret"));

        verify(payments).createOrReuseOrderPaymentIntent(42L);
        verifyNoMoreInteractions(payments);
    }

    @Test
    void getReturnsServerPaymentStatusWithoutClientSecretOrClientVerdict() throws Exception {
        when(payments.getLocalOrderPaymentStatus(42L)).thenReturn(viewWithoutClientSecret("succeeded", 2, "pi_test_42"));

        mockMvc.perform(get("/order/42/payment"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.paymentStatus").value("succeeded"))
                .andExpect(jsonPath("$.data.orderStatus").value(2))
                .andExpect(jsonPath("$.data.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.data.success").doesNotExist())
                .andExpect(jsonPath("$.data.paid").doesNotExist());

        verify(payments).getLocalOrderPaymentStatus(42L);
        verifyNoMoreInteractions(payments);
    }

    @Test
    void syncReturnsServerPaymentStatusWithoutClientSecretOrClientVerdict() throws Exception {
        when(payments.syncOrderPaymentStatusFromStripe(42L)).thenReturn(viewWithoutClientSecret("processing", 0, "pi_test_42"));

        mockMvc.perform(post("/order/42/payment/sync"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.paymentStatus").value("processing"))
                .andExpect(jsonPath("$.data.orderStatus").value(0))
                .andExpect(jsonPath("$.data.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.data.success").doesNotExist())
                .andExpect(jsonPath("$.data.paid").doesNotExist());

        verify(payments).syncOrderPaymentStatusFromStripe(42L);
        verifyNoMoreInteractions(payments);
    }

    @Test
    void webhookWithoutSignatureIsRejectedAsBadRequest() throws Exception {
        doThrow(new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK))
                .when(payments).verifyAndProcessWebhookEvent(anyString(), isNull());

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_test\"}"))
                .andExpect(status().isBadRequest());

        verify(payments).verifyAndProcessWebhookEvent("{\"id\":\"evt_test\"}", null);
        verifyNoMoreInteractions(payments);
    }

    @Test
    void webhookWithValidSignatureReturnsSuccess() throws Exception {
        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", "valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_test\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("received"));

        verify(payments).verifyAndProcessWebhookEvent("{\"id\":\"evt_test\"}", "valid");
        verifyNoMoreInteractions(payments);
    }

    @Test
    void webhookWithInvalidSignatureIsRejectedAsBadRequest() throws Exception {
        doThrow(new StripeGatewayException(StripeGatewayException.Kind.INVALID_WEBHOOK))
                .when(payments).verifyAndProcessWebhookEvent(anyString(), eq("invalid"));

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_test\"}"))
                .andExpect(status().isBadRequest());

        verify(payments).verifyAndProcessWebhookEvent("{\"id\":\"evt_test\"}", "invalid");
        verifyNoMoreInteractions(payments);
    }

    @Test
    void webhookBusinessProcessingFailureIsRetryableServiceUnavailable() throws Exception {
        doThrow(new BusinessException(ResultCodeEnum.BUSINESS_ERROR, "database unavailable"))
                .when(payments).verifyAndProcessWebhookEvent(anyString(), eq("valid"));

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", "valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_test\"}"))
                .andExpect(status().isServiceUnavailable());

        verify(payments).verifyAndProcessWebhookEvent("{\"id\":\"evt_test\"}", "valid");
        verifyNoMoreInteractions(payments);
    }

    @Test
    void webhookUnhandledRuntimeFailureIsRetryableServiceUnavailable() throws Exception {
        doThrow(new IllegalStateException("processing failed"))
                .when(payments).verifyAndProcessWebhookEvent(anyString(), eq("valid"));

        mockMvc.perform(post("/webhooks/stripe")
                        .header("Stripe-Signature", "valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_test\"}"))
                .andExpect(status().isServiceUnavailable());

        verify(payments).verifyAndProcessWebhookEvent("{\"id\":\"evt_test\"}", "valid");
        verifyNoMoreInteractions(payments);
    }

    private static StripePaymentView view(String paymentStatus, int orderStatus, String paymentIntentId) {
        return new StripePaymentView(42L, paymentIntentId, "pi_test_secret", "pk_test_public", 1234L,
                "cny", paymentStatus, orderStatus);
    }

    private static StripePaymentView viewWithoutClientSecret(String paymentStatus, int orderStatus,
                                                              String paymentIntentId) {
        return new StripePaymentView(42L, paymentIntentId, null, "pk_test_public", 1234L,
                "cny", paymentStatus, orderStatus);
    }
}
