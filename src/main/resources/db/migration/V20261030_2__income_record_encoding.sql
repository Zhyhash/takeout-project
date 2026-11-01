-- Convert the settlement ledger to integer event codes and rename account_transaction
-- to income_record, then maintain cumulative settled income on both accounts.
-- Historical string codes are converted by explicit value mapping, never by ordinal;
-- the table CHECK guarantees only the four known values can exist.
-- Fresh deployments may already contain income_record while the earlier migration
-- created the legacy table; copy any legacy rows before removing that table.

SET @takeout_income_record_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'income_record'
);
SET @takeout_account_transaction_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'account_transaction'
);
SET @takeout_copy_legacy_ledger = (@takeout_account_transaction_exists = 1);
SET @takeout_create_income_record = (@takeout_income_record_exists = 0);

SET @takeout_create_income_record_sql = IF(
    @takeout_create_income_record,
    'CREATE TABLE `income_record` (
        `id` bigint NOT NULL AUTO_INCREMENT,
        `beneficiary_type` tinyint NOT NULL COMMENT ''收益归属类型：1-骑手, 2-商家'',
        `beneficiary_id` bigint NOT NULL COMMENT ''收益归属对象 ID'',
        `order_id` bigint NOT NULL COMMENT ''关联订单 ID'',
        `type` tinyint NOT NULL COMMENT ''流水类型：1-配送报酬, 2-订单收入'',
        `amount` decimal(12, 2) NOT NULL COMMENT ''收益金额'',
        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'',
        PRIMARY KEY (`id`),
        UNIQUE KEY `uk_income_record_beneficiary_order_type`
            (`beneficiary_type`, `beneficiary_id`, `order_id`, `type`),
        UNIQUE KEY `uk_income_record_order_type` (`order_id`, `type`),
        KEY `idx_income_record_beneficiary_time`
            (`beneficiary_type`, `beneficiary_id`, `create_time`, `id`),
        CONSTRAINT `chk_income_record_event_identity` CHECK (
            (`beneficiary_type` = 1 AND `type` = 1)
            OR (`beneficiary_type` = 2 AND `type` = 2)
        ),
        CONSTRAINT `chk_income_record_nonnegative_amount` CHECK (`amount` >= 0.00),
        CONSTRAINT `fk_income_record_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
            ON DELETE RESTRICT ON UPDATE RESTRICT
    ) ENGINE = InnoDB CHARACTER SET = utf8mb4
      COLLATE = utf8mb4_0900_ai_ci COMMENT = ''商家与骑手收益流水''',
    'SELECT 1 AS income_record_already_exists'
);
PREPARE takeout_create_income_record_statement FROM @takeout_create_income_record_sql;
EXECUTE takeout_create_income_record_statement;
DEALLOCATE PREPARE takeout_create_income_record_statement;

SET @takeout_copy_income_records_sql = IF(
    @takeout_copy_legacy_ledger,
    'INSERT INTO `income_record`
        (id, beneficiary_type, beneficiary_id, order_id, type, amount, create_time)
     SELECT src.id,
            CASE src.beneficiary_type WHEN ''RIDER'' THEN 1 WHEN ''MERCHANT'' THEN 2 END,
            src.beneficiary_id,
            src.order_id,
            CASE src.type WHEN ''DELIVERY_REWARD'' THEN 1 WHEN ''ORDER_INCOME'' THEN 2 END,
            src.amount,
            src.create_time
     FROM `account_transaction` src
     WHERE NOT EXISTS (
         SELECT 1 FROM `income_record` ir
         WHERE ir.order_id = src.order_id
           AND ir.type = CASE src.type WHEN ''DELIVERY_REWARD'' THEN 1 WHEN ''ORDER_INCOME'' THEN 2 END
           AND ir.beneficiary_type = CASE src.beneficiary_type WHEN ''RIDER'' THEN 1 WHEN ''MERCHANT'' THEN 2 END
           AND ir.beneficiary_id = src.beneficiary_id
           AND ir.amount = src.amount
     )',
    'SELECT 1 AS income_records_already_converted'
);
PREPARE takeout_copy_income_records_statement FROM @takeout_copy_income_records_sql;
EXECUTE takeout_copy_income_records_statement;
DEALLOCATE PREPARE takeout_copy_income_records_statement;

SET @takeout_drop_account_transaction_sql = IF(
    @takeout_copy_legacy_ledger,
    'DROP TABLE `account_transaction`',
    'SELECT 1 AS account_transaction_already_removed'
);
PREPARE takeout_drop_account_transaction_statement FROM @takeout_drop_account_transaction_sql;
EXECUTE takeout_drop_account_transaction_statement;
DEALLOCATE PREPARE takeout_drop_account_transaction_statement;

SET @takeout_merchant_total_income_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'merchant'
      AND column_name = 'total_income'
);
SET @takeout_merchant_total_income_sql = IF(
    @takeout_merchant_total_income_exists = 0,
    'ALTER TABLE `merchant` ADD COLUMN `total_income` DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT ''历史累计已结算收入'' AFTER `balance`',
    'SELECT 1 AS merchant_total_income_already_exists'
);
PREPARE takeout_merchant_total_income_statement FROM @takeout_merchant_total_income_sql;
EXECUTE takeout_merchant_total_income_statement;
DEALLOCATE PREPARE takeout_merchant_total_income_statement;

SET @takeout_rider_total_income_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'rider'
      AND column_name = 'total_income'
);
SET @takeout_rider_total_income_sql = IF(
    @takeout_rider_total_income_exists = 0,
    'ALTER TABLE `rider` ADD COLUMN `total_income` DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT ''历史累计已结算收入'' AFTER `balance`',
    'SELECT 1 AS rider_total_income_already_exists'
);
PREPARE takeout_rider_total_income_statement FROM @takeout_rider_total_income_sql;
EXECUTE takeout_rider_total_income_statement;
DEALLOCATE PREPARE takeout_rider_total_income_statement;

-- Backfill cumulative income from the converted ledger. The migration runs before the
-- application (and any new settlement write) restarts, and the absolute recompute from
-- the ledger converges even if re-executed, so no double counting is possible.
UPDATE `merchant` m
SET m.`total_income` = (
    SELECT COALESCE(SUM(ir.`amount`), 0.00)
    FROM `income_record` ir
    WHERE ir.`beneficiary_type` = 2 AND ir.`beneficiary_id` = m.`id`
);
UPDATE `rider` r
SET r.`total_income` = (
    SELECT COALESCE(SUM(ir.`amount`), 0.00)
    FROM `income_record` ir
    WHERE ir.`beneficiary_type` = 1 AND ir.`beneficiary_id` = r.`id`
);
