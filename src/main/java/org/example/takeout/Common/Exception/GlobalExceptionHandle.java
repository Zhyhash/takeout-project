package org.example.takeout.Common.Exception;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Common.Result.ResultCodeEnum;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Objects;
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandle {

    @ExceptionHandler(value = BusinessException.class)
    public Result<?> BusinessExceptionHandle(BusinessException e) {
        String message = e.getMessage();
        log.warn("业务异常：{}", message);
        return Result.error(ResultCodeEnum.BUSINESS_ERROR,message);
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<?> MethodArgumentNotValidExceptionHandle(MethodArgumentNotValidException e) {
        String defaultMessage = e.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验异常：{}", defaultMessage);
        return Result.error(ResultCodeEnum.PARAM_ERROR,defaultMessage);
    }

    @ExceptionHandler(value = ConstraintViolationException.class)
    public Result<?> ConstraintViolationExceptionHandle(ConstraintViolationException e) {
        String message = Objects.requireNonNull(e.getConstraintViolations().iterator().next()).getMessage();
        log.warn("参数id最小值校检异常：{}", message);
        return Result.error(ResultCodeEnum.PARAM_ERROR,message);
    }

    @ExceptionHandler(value = HandlerMethodValidationException.class)
    public Result<?> handlerMethodValidationExceptionHandle(HandlerMethodValidationException e) {
        String message = e.getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("参数校验失败");
        log.warn("方法参数校验异常：{}", message);
        return Result.error(ResultCodeEnum.PARAM_ERROR, message);
    }

    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public Result<?> missingServletRequestParameterExceptionHandle(MissingServletRequestParameterException e) {
        String message = "缺少请求参数：" + e.getParameterName();
        log.warn("请求参数缺失：{}", e.getParameterName());
        return Result.error(ResultCodeEnum.PARAM_ERROR, message);
    }

    @ExceptionHandler(value = AuthException.class)
    public Result<?> AuthExceptionHandle(AuthException e) {
        String message = e.getMessage();
        log.warn("非法登录尝试{}", message);
        return Result.error(ResultCodeEnum.UNAUTHORIZED,message);
    }

    @ExceptionHandler(value = CartItemInvalidException.class)
    public Result<?> CartItemInvalidExceptionHandle(CartItemInvalidException e) {
        String message = e.getMessage();
        log.warn("购物车商品异常{}", message);
        return Result.error(ResultCodeEnum.BUSINESS_ERROR,message);
    }

    @ExceptionHandler(value = ExpiredJwtException.class)
    public Result<?> handleExpiredJwtException(ExpiredJwtException e) {
        // Result 是你统一返回的响应体对象
        log.warn("凭证失效异常{}",e.getMessage());
        return Result.error(ResultCodeEnum.UNAUTHORIZED,"登录已过期，请重新登录");
    }

    // 捕获 Token 签名错误（被篡改）异常
    @ExceptionHandler(value = SignatureException.class)
    public Result<?> handleSignatureException(SignatureException e) {
        log.warn("非法token登录{}",e.getMessage());
        return Result.error(ResultCodeEnum.UNAUTHORIZED,"Token 签名无效，不合法的访问");
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<?> handleDuplicateKeyException(DuplicateKeyException e) {
        // 也可以通过 e.getMessage() 进一步解析是哪个字段冲突，这里先给友好提示
        log.warn("重复创建数据{}",e.getMessage());
        return Result.error(ResultCodeEnum.DATABASE_ERROR,"数据已存在，请勿重复创建（名称或编码冲突）");
    }
    @ExceptionHandler(value = Exception.class)
    public  Result<?> GloballyExceptionHandle(Exception e) {
        log.error("未明确异常：", e);
        return Result.error(ResultCodeEnum.UNKNOWN_ERROR,"服务器繁忙，请稍后再试或联系管理员");
    }

}
