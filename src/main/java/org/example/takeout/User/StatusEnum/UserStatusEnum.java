package org.example.takeout.User.StatusEnum;

import lombok.Getter;

@Getter
public enum UserStatusEnum {

    NORMAL(1, "正常"),

    DISABLED(0, "禁用"),

    UNACTIVATED(2, "未激活"),

    DELETED(-1, "已注销");

    private final Integer code;
    private final String description;

    // 构造函数
    UserStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    // 获取状态码
    public Integer getCode() {
        return code;
    }

    // 获取状态描述
    public String getDescription() {
        return description;
    }

    /**
     * 根据状态码获取对应的枚举对象
     * * @param code 状态码
     * @return UserStatusEnum 或 null
     */
    public static UserStatusEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (UserStatusEnum status : UserStatusEnum.values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
