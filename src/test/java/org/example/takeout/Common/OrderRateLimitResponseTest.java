package org.example.takeout.Common;

import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Exception.GlobalExceptionHandle;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderRateLimitResponseTest {

    @Test
    void orderRateLimitKeepsItsResponseCode() {
        BusinessException exception = new BusinessException(
                ResultCodeEnum.RATE_LIMIT_EXCEEDED, "操作过于频繁，请稍后再试");

        Result<?> response = new GlobalExceptionHandle().BusinessExceptionHandle(exception);

        assertEquals(ResultCodeEnum.RATE_LIMIT_EXCEEDED.getCode(), response.getCode());
        assertEquals("操作过于频繁，请稍后再试", response.getMessage());
    }
}
