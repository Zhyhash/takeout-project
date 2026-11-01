package org.example.takeout.Settlement.Enums;

import lombok.Getter;

@Getter
public enum BeneficiaryTypeEnum {
    RIDER(1, "骑手"),
    MERCHANT(2, "商家");

    private final int code;
    private final String description;

    BeneficiaryTypeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
