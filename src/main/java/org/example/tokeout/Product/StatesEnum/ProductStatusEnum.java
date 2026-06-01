package org.example.tokeout.Product.StatesEnum;

import lombok.Getter;
@Getter
public enum ProductStatusEnum {
    ON_SALE(0,"正在销售"),
    OFF_SALE(1,"已经下架"),
    SALE_OUT(2,"已售罄"),
    DELETE(3,"已删除");

    private final Integer code;
    private final String description;
    ProductStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

}
