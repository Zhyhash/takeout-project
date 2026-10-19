-- Persist whether a paid order has been presented to its merchant.
-- Existing unpaid/in-payment/pending orders are left unseen so rollout cannot silently miss one;
-- orders that have entered preparation or a later state are treated as already seen.
SET @takeout_merchant_seen_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'merchant_seen'
);
SET @takeout_merchant_seen_add_sql = IF(
    @takeout_merchant_seen_column_exists = 0,
    'ALTER TABLE `orders` ADD COLUMN `merchant_seen` TINYINT(1) NULL DEFAULT NULL COMMENT ''商家是否已查看订单'' AFTER `pay_time`',
    'SELECT 1 AS merchant_seen_already_exists'
);
PREPARE takeout_merchant_seen_add_statement FROM @takeout_merchant_seen_add_sql;
EXECUTE takeout_merchant_seen_add_statement;
DEALLOCATE PREPARE takeout_merchant_seen_add_statement;

-- WAIT_PAY=0, PAYING=1, PAID=2. Keep their current unseen state and avoid changing update_time.
UPDATE orders
SET merchant_seen = CASE WHEN status IN (0, 1, 2) THEN 0 ELSE 1 END,
    update_time = update_time
WHERE merchant_seen IS NULL
   OR (merchant_seen = 0 AND status NOT IN (0, 1, 2));

ALTER TABLE orders
    MODIFY COLUMN merchant_seen TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '商家是否已查看订单';

-- Supports reconnect recovery by merchant/status/seen while preserving display order.
SET @takeout_merchant_seen_index_exists = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'orders'
        GROUP BY index_name
        HAVING COUNT(*) = 5
           AND SUM(seq_in_index = 1 AND column_name = 'merchant_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'status') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'merchant_seen') = 1
           AND SUM(seq_in_index = 4 AND column_name = 'create_time') = 1
           AND SUM(seq_in_index = 5 AND column_name = 'id') = 1
           AND SUM(sub_part IS NOT NULL) = 0
    ) AS matching_indexes
);
SET @takeout_merchant_seen_index_sql = IF(
    @takeout_merchant_seen_index_exists > 0,
    'SELECT 1 AS merchant_seen_index_already_exists',
    'CREATE INDEX `idx_order_merchant_status_seen_create_time_id` ON `orders` (`merchant_id`, `status`, `merchant_seen`, `create_time`, `id`)'
);
PREPARE takeout_merchant_seen_index_statement FROM @takeout_merchant_seen_index_sql;
EXECUTE takeout_merchant_seen_index_statement;
DEALLOCATE PREPARE takeout_merchant_seen_index_statement;
