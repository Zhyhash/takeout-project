package org.example.takeout.Category.StatusEnum;

import lombok.Getter;

@Getter
public enum CategoryStatusEnum {
    ACTIVE(0,"正常使用"),
    INACTIVE(1,"非法分类");

    private final Integer code;
    private final String desc;
    CategoryStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

}
