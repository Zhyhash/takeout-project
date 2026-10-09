-- Add and backfill the next eligible timeout-cancellation time for old orders.
-- The default keeps inserts from older application instances compatible during rollout.
SET @takeout_timeout_cancel_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'timeout_cancel_available_at'
);
SET @takeout_timeout_cancel_add_sql = IF(
    @takeout_timeout_cancel_column_exists = 0,
    'ALTER TABLE `orders` ADD COLUMN `timeout_cancel_available_at` DATETIME NULL DEFAULT NULL COMMENT ''下次允许超时取消扫描的时间'' AFTER `create_time`',
    'SELECT 1 AS timeout_cancel_available_at_already_exists'
);
PREPARE takeout_timeout_cancel_add_statement FROM @takeout_timeout_cancel_add_sql;
EXECUTE takeout_timeout_cancel_add_statement;
DEALLOCATE PREPARE takeout_timeout_cancel_add_statement;

UPDATE orders
SET timeout_cancel_available_at = DATE_ADD(create_time, INTERVAL 30 MINUTE)
WHERE timeout_cancel_available_at IS NULL;

ALTER TABLE orders
    MODIFY COLUMN timeout_cancel_available_at DATETIME NOT NULL
        DEFAULT (CURRENT_TIMESTAMP + INTERVAL 30 MINUTE)
        COMMENT '下次允许超时取消扫描的时间';

-- Reuse an equivalent existing index even if it has a different name.
SET @takeout_timeout_cancel_index_exists = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'orders'
        GROUP BY index_name
        HAVING COUNT(*) = 3
           AND SUM(seq_in_index = 1 AND column_name = 'status') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'timeout_cancel_available_at') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'id') = 1
           AND SUM(sub_part IS NOT NULL) = 0
    ) AS matching_indexes
);
SET @takeout_timeout_cancel_index_sql = IF(
    @takeout_timeout_cancel_index_exists > 0,
    'SELECT 1 AS timeout_cancel_scan_index_already_exists',
    'CREATE INDEX `idx_order_status_timeout_cancel_time_id` ON `orders` (`status`, `timeout_cancel_available_at`, `id`)'
);
PREPARE takeout_timeout_cancel_index_statement FROM @takeout_timeout_cancel_index_sql;
EXECUTE takeout_timeout_cancel_index_statement;
DEALLOCATE PREPARE takeout_timeout_cancel_index_statement;
