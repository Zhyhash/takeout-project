package org.example.takeout.DeliveryTask.Enums;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

    public static String descriptionOf(Integer code) {
        if (code == null) {
            log.warn("配送任务状态码为空，返回未知状态");
            return "未知状态";
        }
        for (DeliveryTaskEnums status : values()) {
            if (status.code.equals(code)) {
                return status.msg;
            }
        }
        log.warn("未识别的配送任务状态码：{}", code);
        return "未知状态";
    }
}
