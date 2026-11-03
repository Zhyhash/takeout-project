package org.example.takeout.Payment.Controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Payment.Service.StripePaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/order/{id}/payment")
@ConditionalOnProperty(prefix = "payment.stripe", name = "enabled", havingValue = "true")
public class StripePaymentController {
    private final StripePaymentService paymentService;

    @PostMapping
    public ResponseEntity<?> createOrReuseOrderPaymentIntent(@PathVariable("id") @Positive Long orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(paymentService.createOrReuseOrderPaymentIntent(orderId)));
    }

    @GetMapping
    public ResponseEntity<?> getLocalOrderPaymentStatus(@PathVariable("id") @Positive Long orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(paymentService.getLocalOrderPaymentStatus(orderId)));
    }

    @PostMapping("/sync")
    public ResponseEntity<?> syncOrderPaymentStatusFromStripe(@PathVariable("id") @Positive Long orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(paymentService.syncOrderPaymentStatusFromStripe(orderId)));
    }
}
