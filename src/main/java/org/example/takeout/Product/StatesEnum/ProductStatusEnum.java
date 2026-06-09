package org.example.takeout.Product.StatesEnum;

import lombok.Getter;
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
}
