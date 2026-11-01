-- The orders table lacks the delivery fee snapshot that OrderTransactionExecutor
-- writes since the distance-based DeliveryFeeCalculator was introduced.
-- Preserve successful migration checksums; repair the schema in a new version.
SET @orders_delivery_fee_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'delivery_fee'
);
SET @orders_delivery_fee_sql = IF(
    @orders_delivery_fee_exists = 0,
    'ALTER TABLE `orders` ADD COLUMN `delivery_fee` DECIMAL(10, 2) NULL COMMENT ''配送费'' AFTER `total_amount`',
    'SELECT 1 AS delivery_fee_already_exists'
);
PREPARE orders_delivery_fee_statement FROM @orders_delivery_fee_sql;
EXECUTE orders_delivery_fee_statement;
DEALLOCATE PREPARE orders_delivery_fee_statement;

-- Orders created before the distance-based fee never charged delivery;
-- only missing snapshots use this historical fallback; existing values stay.
UPDATE orders
SET delivery_fee = 0.00,
    update_time = update_time
WHERE delivery_fee IS NULL;

ALTER TABLE orders
    MODIFY COLUMN delivery_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00
        COMMENT '配送费';
