package org.example.takeout.Payment.Controller;

import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Payment.Gateway.StripeGatewayException;
import org.example.takeout.Payment.Service.StripePaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripeWebhookController {
    private final StripePaymentService paymentService;

    @PostMapping(value = "/webhooks/stripe", consumes = "application/json")
    public ResponseEntity<String> receiveStripeWebhookEvent(@RequestBody String rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        try {
            paymentService.verifyAndProcessWebhookEvent(rawBody, signature);

            return ResponseEntity.ok("received");
        } catch (StripeGatewayException invalid) {
            return ResponseEntity.status(invalid.getKind() == StripeGatewayException.Kind.INVALID_WEBHOOK ? 400 : 503)
                    .body("event not processed");
        } catch (BusinessException invalid) {
            return ResponseEntity.status(invalid.getCodeEnum() == ResultCodeEnum.PARAM_ERROR ? 400 : 503)
                    .body("event not processed");
        } catch (RuntimeException failure) {
            // Do not let the generic error handler return HTTP 200 or log payment payloads.
            return ResponseEntity.status(503).body("event not processed");
        }
    }
}
