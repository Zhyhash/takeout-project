package org.example.takeout.Common.Result;

import lombok.Getter;

@Getter
public enum ResultCodeEnum {
    UNKNOWN_ERROR(-1,"未知异常"),

    SUCCESS(200, "成功"),

    DATABASE_ERROR(301,"数据库异常"),

    PARAM_ERROR(400, "参数错误"),

    UNAUTHORIZED(401, "未登录"),

    FORBIDDEN(403, "无权限"),

    BUSINESS_ERROR(500, "业务异常"),

    SERVER_ERROR(5000, "服务器异常");

    private final Integer code;
    private final String message;

    ResultCodeEnum(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
