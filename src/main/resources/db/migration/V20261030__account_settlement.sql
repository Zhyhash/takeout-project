-- Internal merchant and rider settlement balances and immutable order income entries.
-- Existing accounts start at zero because historical orders are not backfilled as income.
-- Column checks allow deploy/schema.sql to initialize an empty database before Flyway runs.

SET @takeout_merchant_balance_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'merchant'
      AND column_name = 'balance'
);
SET @takeout_merchant_balance_sql = IF(
    @takeout_merchant_balance_exists = 0,
    'ALTER TABLE `merchant` ADD COLUMN `balance` DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT ''当前账户余额'' AFTER `version`',
    'SELECT 1 AS merchant_balance_already_exists'
);
PREPARE takeout_merchant_balance_statement FROM @takeout_merchant_balance_sql;
EXECUTE takeout_merchant_balance_statement;
DEALLOCATE PREPARE takeout_merchant_balance_statement;

SET @takeout_rider_balance_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'rider'
      AND column_name = 'balance'
);
SET @takeout_rider_balance_sql = IF(
    @takeout_rider_balance_exists = 0,
    'ALTER TABLE `rider` ADD COLUMN `balance` DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT ''当前账户余额'' AFTER `is_delete`',
    'SELECT 1 AS rider_balance_already_exists'
);
PREPARE takeout_rider_balance_statement FROM @takeout_rider_balance_sql;
EXECUTE takeout_rider_balance_statement;
DEALLOCATE PREPARE takeout_rider_balance_statement;

CREATE TABLE IF NOT EXISTS `account_transaction` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `beneficiary_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '收益归属类型：RIDER 或 MERCHANT',
    `beneficiary_id` bigint NOT NULL COMMENT '收益归属对象 ID',
    `order_id` bigint NOT NULL COMMENT '关联订单 ID',
    `type` varchar(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '流水类型：DELIVERY_REWARD 或 ORDER_INCOME',
    `amount` decimal(12, 2) NOT NULL COMMENT '收益金额',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_transaction_beneficiary_order_type`
        (`beneficiary_type`, `beneficiary_id`, `order_id`, `type`),
    UNIQUE KEY `uk_account_transaction_order_type` (`order_id`, `type`),
    KEY `idx_account_transaction_beneficiary_time`
        (`beneficiary_type`, `beneficiary_id`, `create_time`, `id`),
    CONSTRAINT `chk_account_transaction_event_identity` CHECK (
        (`beneficiary_type` = 'RIDER' AND `type` = 'DELIVERY_REWARD')
        OR (`beneficiary_type` = 'MERCHANT' AND `type` = 'ORDER_INCOME')
    ),
    CONSTRAINT `chk_account_transaction_nonnegative_amount` CHECK (`amount` >= 0.00),
    CONSTRAINT `fk_account_transaction_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '商家与骑手收益流水';
