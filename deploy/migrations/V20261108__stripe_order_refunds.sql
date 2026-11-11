-- One refund record per order; an unknown creation result keeps the original idempotency key.
CREATE TABLE IF NOT EXISTS `order_stripe_refunds` (
    `order_id` bigint NOT NULL,
    `refund_id` varchar(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `amount_minor` bigint NOT NULL,
    `currency` varchar(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `idempotency_key` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `stripe_status` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `reconciliation_state` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_id`),
    UNIQUE KEY `uk_order_stripe_refund_id` (`refund_id`),
    UNIQUE KEY `uk_order_stripe_refund_idempotency` (`idempotency_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
