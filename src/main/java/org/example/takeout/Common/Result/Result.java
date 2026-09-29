package org.example.takeout.Common.Result;

import lombok.Data;

@Data
public class Result<T> {
    int code;//正常/报错编码
    String message;//报错信息
    T data;//返回信息

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.data = data;
        result.code = ResultCodeEnum.SUCCESS.getCode();
        result.message = "success";
        return result;
    }
    public static <T> Result<T> error(ResultCodeEnum codeEnum,String message) {
        Result<T> result = new Result<>();
        result.code = codeEnum.getCode();
        result.message = message;
        return result;
    }
}
