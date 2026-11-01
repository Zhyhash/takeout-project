-- Test-only Stripe references. Order status remains in orders; no payment secrets are persisted.
CREATE TABLE IF NOT EXISTS `order_stripe_payments` (
    `order_id` bigint NOT NULL,
    `payment_intent_id` varchar(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `amount_minor` bigint NOT NULL COMMENT 'CNY fen, copied from the server-side order total',
    `currency` char(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'cny',
    `stripe_status` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `idempotency_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_id`),
    UNIQUE KEY `uk_order_stripe_payment_intent` (`payment_intent_id`),
    UNIQUE KEY `uk_order_stripe_idempotency` (`idempotency_key`),
    CONSTRAINT `chk_order_stripe_cny` CHECK (`currency` = 'cny'),
    CONSTRAINT `chk_order_stripe_amount` CHECK (`amount_minor` > 0 AND `amount_minor` <= 99999999),
    CONSTRAINT `fk_order_stripe_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `stripe_payment_events` (
    `event_id` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `payment_intent_id` varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_type` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`event_id`),
    KEY `idx_stripe_payment_events_intent` (`payment_intent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
