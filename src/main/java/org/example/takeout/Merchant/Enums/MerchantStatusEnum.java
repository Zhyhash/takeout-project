package org.example.takeout.Merchant.Enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public enum MerchantStatusEnum {
    BUSINESS_OPEN(0,"店铺正常开启"),
    BUSINESS_CLOSED(1,"店铺已经打烊");

    @JsonValue
    private final Integer code;
    private final String msg;

    MerchantStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static String descriptionOf(Integer code) {
        if (code == null) {
            log.warn("商家状态码为空，返回未知状态");
            return "未知状态";
        }
        for (MerchantStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status.msg;
            }
        }
        log.warn("未识别的商家状态码：{}", code);
        return "未知状态";
    }
}
