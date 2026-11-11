package org.example.takeout.Payment.Domain;

public enum RefundSyncResult {
    REFUNDED,   // 本次首次完成退款，需要归还库存
    UNCHANGED,  // 状态一致，无需操作
    UPDATED,    // 状态发生变化，但不需要归还库存
    CONFLICT    // 状态冲突，需要进一步处理
}
