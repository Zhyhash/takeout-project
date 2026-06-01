package org.example.tokeout.Common.Exception;

import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.example.tokeout.Common.Result.Result;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandle {

    @ExceptionHandler(value = BusinessException.class)
    public Result<?> BusinessExceptionHandle(BusinessException e) {
        String message = e.getMessage();
        log.warn("业务异常：{}", message);
        return Result.error(message);
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<?> MethodArgumentNotValidExceptionHandle(MethodArgumentNotValidException e) {
        String defaultMessage = Objects.requireNonNull(e.getBindingResult().getFieldError()).getDefaultMessage();
        log.warn("参数校验异常：{}", defaultMessage);
        return Result.error(defaultMessage);
    }

    @ExceptionHandler(value = ConstraintViolationException.class)
    public Result<?> ConstraintViolationExceptionHandle(ConstraintViolationException e) {
        String message = Objects.requireNonNull(e.getConstraintViolations().iterator().next()).getMessage();
        log.warn("参数id最小值校检异常：{}", message);
        return Result.error(message);
    }

    @ExceptionHandler(value = AuthException.class)
    public Result<?> AuthExceptionHandle(AuthException e) {
        String message = e.getMessage();
        log.warn("非法登录尝试{}", message);
        return Result.error(message);
    }

    @ExceptionHandler(value = CartItemInvalidException.class)
    public Result<?> CartItemInvalidExceptionHandle(CartItemInvalidException e) {
        String message = e.getMessage();
        log.warn("购物车商品异常{}", message);
        return Result.error(message);
    }

    @ExceptionHandler(value = Exception.class)
    public  Result<?> GloballyExceptionHandle(Exception e) {
        log.error("未明确异常：", e);
        return Result.error("服务器繁忙，请稍后再试或联系管理员");
    }

}
