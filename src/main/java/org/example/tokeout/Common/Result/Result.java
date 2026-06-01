package org.example.tokeout.Common.Result;

import lombok.Data;

@Data
public class Result<T> {
    int code;//正常/报错编码
    String message;//报错信息
    T data;//返回信息

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.data = data;
        result.code = 200;
        result.message = "success";//必要吗？不返回似乎也可以？（上一次项目success方法没写这个message）
        return result;
    }
    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.code = 500;//需要为每一种code报错都做一个不同的result返回吗？
        result.message = message;
        return result;
    }
}
