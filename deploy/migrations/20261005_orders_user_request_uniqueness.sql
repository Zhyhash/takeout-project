-- Version 20261005: restore the database guard for order creation idempotency.
-- Run manually against the selected application database before accepting orders.
-- Prerequisite: orders.user_id and orders.request_id already exist and are NOT NULL.
-- Pause order writes and serialize migration runs; this is not a startup script.
-- No orders table recreation or business data changes are performed.
--
-- Check for duplicate keys first, using the columns' existing collation:
-- SELECT user_id, request_id, COUNT(*) AS duplicate_count
-- FROM orders
-- GROUP BY user_id, request_id
-- HAVING COUNT(*) > 1;
-- Resolve any duplicates through an explicit business decision, then rerun.
-- MySQL rejects ADD UNIQUE INDEX on duplicates; it never deletes/merges rows.

-- Accept an equivalent full-column unique index even if it has another name.
-- Checking only the index name could mistake a non-unique/wrong index for the guard.
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

-- If the target name is occupied by an incorrect index, ADD fails explicitly.
-- Do not silently drop or replace an existing index.
SET @takeout_order_request_unique_sql = IF(
    @takeout_order_request_unique_exists > 0,
    'SELECT 1 AS orders_user_request_unique_already_exists',
    'ALTER TABLE `orders` ADD UNIQUE INDEX `uk_orders_user_request_id` (`user_id`, `request_id`) USING BTREE'
);
PREPARE takeout_order_request_unique_statement FROM @takeout_order_request_unique_sql;
EXECUTE takeout_order_request_unique_statement;
DEALLOCATE PREPARE takeout_order_request_unique_statement;
