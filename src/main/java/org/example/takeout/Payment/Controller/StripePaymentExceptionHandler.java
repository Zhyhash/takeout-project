package org.example.takeout.Payment.Controller;

import org.example.takeout.Common.Result.Result;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.example.takeout.Payment.Gateway.StripeGatewayException;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-10)
@RestControllerAdvice(assignableTypes = StripePaymentController.class)
public class StripePaymentExceptionHandler {
    @ExceptionHandler(StripeGatewayException.class)
    public ResponseEntity<?> unavailable(StripeGatewayException failure) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(
                Result.error(ResultCodeEnum.BUSINESS_ERROR,
                        "Stripe 测试支付请求失败，请检查受限密钥权限或稍后重试"));
    }
}
