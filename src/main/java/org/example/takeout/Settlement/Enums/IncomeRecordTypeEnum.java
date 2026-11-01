package org.example.takeout.Settlement.Enums;

import lombok.Getter;

@Getter
public enum IncomeRecordTypeEnum {
    DELIVERY_REWARD(1, "配送报酬"),
    ORDER_INCOME(2, "订单收入");

    private final int code;
    private final String description;

    IncomeRecordTypeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
