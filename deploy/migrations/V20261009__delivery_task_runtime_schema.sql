-- The local database has completed V20261008 but still lacks the reward snapshot
-- and AUTO_INCREMENT required by DeliveryTask / Rider (both use IdType.AUTO).
-- Preserve successful migration checksums; repair the schema in a new version.
SET @takeout_delivery_reward_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'delivery_task'
      AND column_name = 'delivery_reward'
);
SET @takeout_delivery_reward_sql = IF(
    @takeout_delivery_reward_exists = 0,
    'ALTER TABLE `delivery_task` ADD COLUMN `delivery_reward` DECIMAL(10, 2) NULL COMMENT ''配送奖励金额快照'' AFTER `merchant_name`',
    'SELECT 1 AS delivery_reward_already_exists'
);
PREPARE takeout_delivery_reward_statement FROM @takeout_delivery_reward_sql;
EXECUTE takeout_delivery_reward_statement;
DEALLOCATE PREPARE takeout_delivery_reward_statement;

-- DeliveryFeeCalculator currently returns 5.00 for every task. Only missing
-- snapshots use this historical fallback; existing rewards and timestamps stay.
UPDATE delivery_task
SET delivery_reward = 5.00,
    update_time = update_time
WHERE delivery_reward IS NULL;

ALTER TABLE delivery_task
    MODIFY COLUMN delivery_reward DECIMAL(10, 2) NOT NULL
        COMMENT '配送奖励金额快照';

SET @takeout_delivery_id_is_auto = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'delivery_task'
      AND column_name = 'id'
      AND extra LIKE '%auto_increment%'
);
SET @takeout_delivery_id_sql = IF(
    @takeout_delivery_id_is_auto = 0,
    'ALTER TABLE `delivery_task` MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT',
    'SELECT 1 AS delivery_task_id_already_auto_increment'
);
PREPARE takeout_delivery_id_statement FROM @takeout_delivery_id_sql;
EXECUTE takeout_delivery_id_statement;
DEALLOCATE PREPARE takeout_delivery_id_statement;

SET @takeout_rider_id_is_auto = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'rider'
      AND column_name = 'id'
      AND extra LIKE '%auto_increment%'
);
SET @takeout_rider_id_sql = IF(
    @takeout_rider_id_is_auto = 0,
    'ALTER TABLE `rider` MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT',
    'SELECT 1 AS rider_id_already_auto_increment'
);
PREPARE takeout_rider_id_statement FROM @takeout_rider_id_sql;
EXECUTE takeout_rider_id_statement;
DEALLOCATE PREPARE takeout_rider_id_statement;
