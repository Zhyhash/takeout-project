package org.example.takeout.Payment.Gateway;

import com.stripe.StripeClient;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StripeRefundGatewayImplTest {
    @Test
    void newRequestsCarryAnAttemptReferenceAndLegacyReplaysKeepTheirOriginalParameters() throws Exception {
        StripeClient client = mock(StripeClient.class, RETURNS_DEEP_STUBS);
        Refund pending = new Refund();
        pending.setId("re_test");
        pending.setPaymentIntent("pi_test");
        pending.setCurrency("cny");
        pending.setStatus("pending");
        pending.setAmount(100L);
        when(client.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(pending);

        StripeRefundGateway gateway = new StripeRefundGatewayImpl(client);
        gateway.createRefund("pi_test", 100, "refund_attempt_test");
        gateway.createRefund("pi_test", 100, "order_refund_42");

        var params = ArgumentCaptor.forClass(RefundCreateParams.class);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.v1().refunds(), times(2)).create(params.capture(), options.capture());
        assertThat(params.getAllValues().get(0).getMetadata()).isEqualTo(Map.of("refund_attempt_key", "refund_attempt_test"));
        assertThat(params.getAllValues().get(1).getMetadata()).isNull();
        assertThat(options.getAllValues()).extracting(RequestOptions::getIdempotencyKey)
                .containsExactly("refund_attempt_test", "order_refund_42");
    }
}
