-- 商家取货坐标与配送任务坐标快照。
--
-- 可接配送任务需要写入 Redis GEO（delivery:available:geo）供骑手按距离查询，
-- 但此前 merchant 只有文本地址，没有经纬度。本迁移补上商家坐标，并在
-- delivery_task 上保存建任务时的坐标快照，避免商家后续改址影响历史任务。
-- 历史行保持 NULL：缺少坐标的任务由业务代码跳过 GEO 写入并记录日志。
--
-- 列存在性检查保证 deploy/schema.sql 已建好列的空库可以安全重复执行本迁移。
-- 不要在本文件被 flyway_schema_history 记录后再修改它。

SET @takeout_merchant_longitude_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'merchant'
      AND column_name = 'longitude'
);
SET @takeout_merchant_longitude_sql = IF(
    @takeout_merchant_longitude_exists = 0,
    'ALTER TABLE `merchant` ADD COLUMN `longitude` DECIMAL(10, 7) NULL COMMENT ''店铺取货经度'' AFTER `address`',
    'SELECT 1 AS merchant_longitude_already_exists'
);
PREPARE takeout_merchant_longitude_statement FROM @takeout_merchant_longitude_sql;
EXECUTE takeout_merchant_longitude_statement;
DEALLOCATE PREPARE takeout_merchant_longitude_statement;

SET @takeout_merchant_latitude_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'merchant'
      AND column_name = 'latitude'
);
SET @takeout_merchant_latitude_sql = IF(
    @takeout_merchant_latitude_exists = 0,
    'ALTER TABLE `merchant` ADD COLUMN `latitude` DECIMAL(10, 7) NULL COMMENT ''店铺取货纬度'' AFTER `longitude`',
    'SELECT 1 AS merchant_latitude_already_exists'
);
PREPARE takeout_merchant_latitude_statement FROM @takeout_merchant_latitude_sql;
EXECUTE takeout_merchant_latitude_statement;
DEALLOCATE PREPARE takeout_merchant_latitude_statement;

SET @takeout_delivery_task_pickup_longitude_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'delivery_task'
      AND column_name = 'pickup_longitude'
);
SET @takeout_delivery_task_pickup_longitude_sql = IF(
    @takeout_delivery_task_pickup_longitude_exists = 0,
    'ALTER TABLE `delivery_task` ADD COLUMN `pickup_longitude` DECIMAL(10, 7) NULL COMMENT ''商家取货经度快照'' AFTER `merchant_address`',
    'SELECT 1 AS delivery_task_pickup_longitude_already_exists'
);
PREPARE takeout_delivery_task_pickup_longitude_statement
    FROM @takeout_delivery_task_pickup_longitude_sql;
EXECUTE takeout_delivery_task_pickup_longitude_statement;
DEALLOCATE PREPARE takeout_delivery_task_pickup_longitude_statement;

SET @takeout_delivery_task_pickup_latitude_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'delivery_task'
      AND column_name = 'pickup_latitude'
);
SET @takeout_delivery_task_pickup_latitude_sql = IF(
    @takeout_delivery_task_pickup_latitude_exists = 0,
    'ALTER TABLE `delivery_task` ADD COLUMN `pickup_latitude` DECIMAL(10, 7) NULL COMMENT ''商家取货纬度快照'' AFTER `pickup_longitude`',
    'SELECT 1 AS delivery_task_pickup_latitude_already_exists'
);
PREPARE takeout_delivery_task_pickup_latitude_statement
    FROM @takeout_delivery_task_pickup_latitude_sql;
EXECUTE takeout_delivery_task_pickup_latitude_statement;
DEALLOCATE PREPARE takeout_delivery_task_pickup_latitude_statement;
