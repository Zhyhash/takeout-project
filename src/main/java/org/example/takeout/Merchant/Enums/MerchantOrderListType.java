package org.example.takeout.Merchant.Enums;

/**
 * 商家订单列表视图。
 */
public enum MerchantOrderListType {
    /** 已支付、等待商家接单。 */
    PENDING,
    /** 商家已经接单，正在制作或已经进入后续履约流程。 */
    ACCEPTED
}
