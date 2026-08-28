-- Existing databases are not changed by CREATE TABLE IF NOT EXISTS in schema.sql.
-- Run this once when deploying the product status lifecycle change.
ALTER TABLE product
    MODIFY COLUMN status tinyint NOT NULL DEFAULT 1
        COMMENT '商品状态: 0-在售, 1-下架, 2-售罄';

-- Normalize active products to the new state invariants.
UPDATE product
SET status = 2,
    version = version + 1
WHERE is_deleted = 0
  AND status = 0
  AND stock = 0;

UPDATE product
SET status = 0,
    version = version + 1
WHERE is_deleted = 0
  AND status = 2
  AND stock > 0;
