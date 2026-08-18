package org.example.takeout.Product.StatesEnum;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public enum ProductStatusEnum {
    ON_SALE(0,"正在销售"),
    OFF_SALE(1,"已经下架"),
    SALE_OUT(2,"已售罄");

    private final Integer code;
    private final String description;
    ProductStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public static String descriptionOf(Integer code) {
        if (code == null) {
            log.warn("商品状态码为空，返回未知状态");
            return "未知状态";
        }
        for (ProductStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status.description;
            }
        }
        log.warn("未识别的商品状态码：{}", code);
        return "未知状态";
    }
}
