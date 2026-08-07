package org.example.takeout.Rider.Enums;

import lombok.Getter;

@Getter
public enum RiderStatusEnum {

    NORMAL(1, "正常"),
    DISABLED(0, "禁用");

    private final Integer code;
    private final String description;

    RiderStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
