package org.example.takeout.Order.Enums;

import lombok.Getter;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Result.ResultCodeEnum;

@Getter
public enum OrderStatusEnum {
    WAIT_PAY(0, "待支付"),
    PAID(1, "已支付"),
    FINISHED(2,"已完成"),
    CANCELLED(3, "已取消");

    // getter 方法
    private final Integer code;   // 数据库存这个数字
    private final String msg;     // 前端显示这个文字

    // 构造方法
    OrderStatusEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    // 根据code获取枚举（从数据库读取时用）
    public static OrderStatusEnum fromCode(Integer code) {
        for (OrderStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new BusinessException(ResultCodeEnum.BUSINESS_ERROR,"订单状态非法");
    }

    public boolean canCancel() {
        return this == WAIT_PAY;
    }

    public boolean canPay() {
        return this == WAIT_PAY;
    }

    public boolean canConfirm() {
        return this == PAID;
    }

    public OrderStatusEnum nextAfterPay() {
        if (this != WAIT_PAY) throw new IllegalStateException("状态不对");
        return PAID;
    }
}
