package org.example.takeout.Merchant.Enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;

@Getter
@Slf4j
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
    public static Integer calculateActualStatus(LocalTime opening,LocalTime closing) {
        if (opening == null || closing ==null) {
            log.warn("传入营业时间或打烊时间为空");
            return BUSINESS_CLOSED.getCode();
        }
        //营业与打烊时间相同直接降级变为打烊，同时记录日志
        if (opening.equals(closing)){
            log.warn("商家/用户传入了相同的营业与打烊时间");
            return BUSINESS_CLOSED.getCode();
        }


        LocalTime now = LocalTime.now();
        //对于不跨天
        boolean isOpen;
        if (closing.isAfter(opening)) {
            // 不跨天：营业时段 [opening, closing)
            isOpen = !now.isBefore(opening) && now.isBefore(closing);
        } else {
            // 跨天：营业时段 [opening, 00:00) ∪ [00:00, closing)
            isOpen = now.isBefore(closing) || now.isAfter(opening);
        }

        return isOpen ? BUSINESS_OPEN.getCode() : BUSINESS_CLOSED.getCode();
    }
}
