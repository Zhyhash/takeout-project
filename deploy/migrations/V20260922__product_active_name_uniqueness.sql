-- Make this migration safe for both the old deployed schema and the current
-- schema.sql, which already contains the generated column and unique index.
SET @takeout_product_old_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'product'
      AND index_name = 'uk_merchant_product'
);

SET @takeout_product_drop_old_index_sql = IF(
    @takeout_product_old_index_exists > 0,
    'ALTER TABLE `product` DROP INDEX `uk_merchant_product`',
    'SELECT 1 AS old_product_index_already_absent'
);
PREPARE takeout_product_drop_old_index_statement FROM @takeout_product_drop_old_index_sql;
EXECUTE takeout_product_drop_old_index_statement;
DEALLOCATE PREPARE takeout_product_drop_old_index_statement;

SET @takeout_product_active_guard_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'product'
      AND column_name = 'active_name_guard'
);

SET @takeout_product_active_guard_add_sql = IF(
    @takeout_product_active_guard_exists = 0,
    'ALTER TABLE `product` ADD COLUMN `active_name_guard` tinyint GENERATED ALWAYS AS (CASE WHEN `is_deleted` = 0 THEN 1 ELSE NULL END) STORED COMMENT ''仅用于约束未删除商品名称唯一''',
    'SELECT 1 AS active_name_guard_already_exists'
);
PREPARE takeout_product_active_guard_add_statement FROM @takeout_product_active_guard_add_sql;
EXECUTE takeout_product_active_guard_add_statement;
DEALLOCATE PREPARE takeout_product_active_guard_add_statement;

SET @takeout_product_unique_exists = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'product'
        GROUP BY index_name
        HAVING MIN(non_unique) = 0
           AND COUNT(*) = 3
           AND SUM(seq_in_index = 1 AND column_name = 'merchant_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'product_name') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'active_name_guard') = 1
           AND SUM(sub_part IS NOT NULL) = 0
    ) AS matching_indexes
);

SET @takeout_product_unique_sql = IF(
    @takeout_product_unique_exists > 0,
    'SELECT 1 AS product_active_name_unique_already_exists',
    'ALTER TABLE `product` ADD UNIQUE INDEX `uk_merchant_product_active` (`merchant_id`, `product_name`, `active_name_guard`) USING BTREE'
);
PREPARE takeout_product_unique_statement FROM @takeout_product_unique_sql;
EXECUTE takeout_product_unique_statement;
DEALLOCATE PREPARE takeout_product_unique_statement;
