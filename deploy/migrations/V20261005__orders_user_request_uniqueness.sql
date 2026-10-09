-- Accept an equivalent full-column unique index even when its name differs.
SET @takeout_order_request_unique_exists = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'orders'
        GROUP BY index_name
        HAVING MIN(non_unique) = 0
           AND COUNT(*) = 2
           AND SUM(seq_in_index = 1 AND column_name = 'user_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'request_id') = 1
           AND SUM(sub_part IS NOT NULL) = 0
    ) AS matching_indexes
);

SET @takeout_order_request_unique_sql = IF(
    @takeout_order_request_unique_exists > 0,
    'SELECT 1 AS orders_user_request_unique_already_exists',
    'ALTER TABLE `orders` ADD UNIQUE INDEX `uk_orders_user_request_id` (`user_id`, `request_id`) USING BTREE'
);
PREPARE takeout_order_request_unique_statement FROM @takeout_order_request_unique_sql;
EXECUTE takeout_order_request_unique_statement;
DEALLOCATE PREPARE takeout_order_request_unique_statement;
