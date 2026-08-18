package org.example.takeout.Order.Enums;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public enum OrderStatusEnum {
    WAIT_PAY(0, "待支付"),
    PAYING(1,"正在支付"),
    PAID(2, "已支付"),
    FINISHED(3,"已完成"),
    CANCELLED(4, "已取消"),
    PREPARING(5,"商家正在制作菜品"),
    READY(6,"菜品制作完成，等待骑手接单"),
    DELIVERING(7,"骑手正在配送"),
    DELIVERED(8,"骑手已经送达");


    // getter 方法
    private final Integer code;   // 数据库存这个数字
    private final String msg;     // 前端显示这个文字

    // 构造方法
    OrderStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static String descriptionOf(Integer code) {
        if (code == null) {
            log.warn("订单状态码为空，返回未知状态");
            return "未知状态";
        }
        for (OrderStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status.msg;
            }
        }
        log.warn("未识别的订单状态码：{}", code);
        return "未知状态";
    }

}
