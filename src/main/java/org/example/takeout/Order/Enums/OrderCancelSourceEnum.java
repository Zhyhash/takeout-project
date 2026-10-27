package org.example.takeout.Order.Enums;

import lombok.Getter;

/**
 * 订单取消来源。
 * 对应 orders.cancel_source，记录订单进入已取消状态的原因。
 */
@Getter
public enum OrderCancelSourceEnum {
    USER(1, "用户主动取消"),
    MERCHANT(2, "商家拒单"),
    TIMEOUT(3, "系统超时取消"),
    PLATFORM(4, "平台取消");

    private final Integer code;   // 数据库存这个数字
    private final String msg;     // 前端显示这个文字

    OrderCancelSourceEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
