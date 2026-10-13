package org.example.takeout.Merchant.Enums;

/**
 * 商家订单列表视图。
 */
public enum MerchantOrderListType {
    /** 已支付、等待商家接单。 */
    PENDING,
    /** 商家已经接单，正在备餐。 */
    PROCESSING,
    /** 商家业务流程已经结束的历史订单。 */
    HISTORY
}
