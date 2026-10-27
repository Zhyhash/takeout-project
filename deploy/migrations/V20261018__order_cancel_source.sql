-- Persist why an order was cancelled so later queries can distinguish the cancel paths.
-- Historical cancelled orders stay NULL because their source cannot be reconstructed reliably.
SET @takeout_cancel_source_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'cancel_source'
);
SET @takeout_cancel_source_add_sql = IF(
    @takeout_cancel_source_column_exists = 0,
    'ALTER TABLE `orders` ADD COLUMN `cancel_source` TINYINT NULL DEFAULT NULL COMMENT ''订单取消原因：1用户主动取消，2商家拒单，3系统超时取消，4平台取消'' AFTER `status`',
    'SELECT 1 AS cancel_source_already_exists'
);
PREPARE takeout_cancel_source_add_statement FROM @takeout_cancel_source_add_sql;
EXECUTE takeout_cancel_source_add_statement;
DEALLOCATE PREPARE takeout_cancel_source_add_statement;
