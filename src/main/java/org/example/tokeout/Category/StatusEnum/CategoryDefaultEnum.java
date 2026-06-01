package org.example.tokeout.Category.StatusEnum;

import lombok.Getter;

@Getter
public enum CategoryDefaultEnum {
    DEFAULT(0,"默认分类"),
    CLASSIFICATION(1,"商家自主分类");
    private final Integer code;
    private final String desc;
    CategoryDefaultEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
