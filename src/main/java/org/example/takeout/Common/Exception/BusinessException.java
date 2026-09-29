package org.example.takeout.Common.Exception;

import lombok.Getter;
import org.example.takeout.Common.Result.ResultCodeEnum;

@Getter
public class BusinessException extends RuntimeException {

    private final ResultCodeEnum codeEnum;

    public BusinessException(ResultCodeEnum codeEnum, String message) {
        super(message);
        this.codeEnum = codeEnum;
    }

    public Integer getCode() {
        return codeEnum.getCode();
    }
}
