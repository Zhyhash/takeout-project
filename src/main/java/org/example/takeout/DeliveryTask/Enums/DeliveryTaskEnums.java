package org.example.takeout.DeliveryTask.Enums;

import lombok.Getter;

@Getter
public enum DeliveryTaskEnums {
    WAIT_ASSIGN(0,"等待骑手取餐"),
    DELIVERING(1,"骑手正在配送"),
    COMPLETED(2,"骑手已经完成配送");

    private final Integer code;
    private final String msg;
    DeliveryTaskEnums(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
