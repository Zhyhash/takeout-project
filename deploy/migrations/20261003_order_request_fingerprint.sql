-- Run against the selected application database before deploying the matching
-- Java code. Pause order writes during this migration. Existing order snapshots
-- and the unique key (user_id, request_id) are preserved.
-- This script can be repeated whether request_hash already exists or not.
SET NAMES utf8mb4;

SET @takeout_order_hash_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'orders'
      AND column_name = 'request_hash'
);
SET @takeout_order_hash_add_sql = IF(
    @takeout_order_hash_column_exists = 0,
    'ALTER TABLE `orders` ADD COLUMN `request_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''收货信息和备注的SHA-256请求指纹'' AFTER `request_id`',
    'SELECT 1 AS request_hash_column_already_exists'
);
PREPARE takeout_order_hash_add_statement FROM @takeout_order_hash_add_sql;
EXECUTE takeout_order_hash_add_statement;
DEALLOCATE PREPARE takeout_order_hash_add_statement;

-- Match OrderRequestFingerprint exactly: NULL becomes N:, while each non-null
-- field becomes <Java String.length()>:<value>. UTF-16 byte length / 2 counts
-- surrogate pairs as two code units, including emoji; CHAR_LENGTH alone does not.
-- Hash the concatenation in receiver_name, receiver_phone, receiver_address,
-- remark order using UTF-8. Preserve already valid fingerprints and update_time.
UPDATE orders
SET request_hash = SHA2(CONVERT(CONCAT(
        CASE WHEN receiver_name IS NULL THEN 'N:'
             ELSE CONCAT(OCTET_LENGTH(CONVERT(receiver_name USING utf16)) DIV 2,
                         ':', receiver_name) END,
        CASE WHEN receiver_phone IS NULL THEN 'N:'
             ELSE CONCAT(OCTET_LENGTH(CONVERT(receiver_phone USING utf16)) DIV 2,
                         ':', receiver_phone) END,
        CASE WHEN receiver_address IS NULL THEN 'N:'
             ELSE CONCAT(OCTET_LENGTH(CONVERT(receiver_address USING utf16)) DIV 2,
                         ':', receiver_address) END,
        CASE WHEN remark IS NULL THEN 'N:'
             ELSE CONCAT(OCTET_LENGTH(CONVERT(remark USING utf16)) DIV 2,
                         ':', remark) END
    ) USING utf8mb4), 256),
    update_time = update_time
WHERE request_hash IS NULL
   OR NOT REGEXP_LIKE(request_hash, '^[0-9a-f]{64}$', 'c');

-- No empty default: every future order insert must provide its real fingerprint.
ALTER TABLE orders
    MODIFY COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL COMMENT '收货信息和备注的SHA-256请求指纹' AFTER request_id;
