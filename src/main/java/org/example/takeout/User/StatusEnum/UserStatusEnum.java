package org.example.takeout.User.StatusEnum;

import lombok.Getter;

@Getter
public enum UserStatusEnum {

    NORMAL(1),

    DISABLED(0),

    UNACTIVATED(2),

    DELETED(-1);

    private final Integer code;

    // 构造函数
    UserStatusEnum(Integer code) {
        this.code = code;
    }

}
