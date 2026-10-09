-- The local schema allows NULL product categories and lacks the merchant
-- version default. Match schema.sql without inventing categories for old rows.
DROP TEMPORARY TABLE IF EXISTS takeout_product_category_preflight;
CREATE TEMPORARY TABLE takeout_product_category_preflight (
    null_categories BIGINT NOT NULL,
    orphan_categories BIGINT NOT NULL,
    CONSTRAINT chk_product_category_not_null CHECK (null_categories = 0),
    CONSTRAINT chk_product_category_exists CHECK (orphan_categories = 0)
) ENGINE = InnoDB;

INSERT INTO takeout_product_category_preflight
SELECT
    (SELECT COUNT(*) FROM product WHERE category_id IS NULL),
    (SELECT COUNT(*)
     FROM product p LEFT JOIN category c ON c.id = p.category_id
     WHERE p.category_id IS NOT NULL AND c.id IS NULL);

DROP TEMPORARY TABLE takeout_product_category_preflight;

ALTER TABLE product
    MODIFY COLUMN category_id BIGINT NOT NULL COMMENT '分类ID',
    MODIFY COLUMN stock INT NOT NULL DEFAULT 0 COMMENT '库存数量';

ALTER TABLE merchant
    MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

ALTER TABLE cache_invalidation_task
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
        COMMENT '任务状态: 0-PENDING, 1-SUCCESS, 2-FAILED';
