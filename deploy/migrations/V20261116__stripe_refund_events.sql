-- 记录已处理的 Stripe 退款 Webhook 事件，供重复投递去重。
-- 与 stripe_payment_events 分开存放：退款事件按 refund_id 归属，暂不合并两条链路的回执表。
CREATE TABLE IF NOT EXISTS `stripe_refund_events` (
    `event_id` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `refund_id` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_type` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`event_id`),
    KEY `idx_stripe_refund_events_refund` (`refund_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
