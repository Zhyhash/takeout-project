-- 逻辑删除的商品不再占用名称；恢复后会重新占用名称。
-- 同一商家仍只能有一个未删除的同名商品，创建与恢复的并发冲突均由该唯一索引兜底。
-- 已手工执行过相同变更的数据库不要重复运行本迁移。
ALTER TABLE product
    DROP INDEX uk_merchant_product,
    ADD COLUMN active_name_guard tinyint
        GENERATED ALWAYS AS (
            CASE WHEN is_deleted = 0 THEN 1 ELSE NULL END
        ) STORED
        COMMENT '仅用于约束未删除商品名称唯一',
    ADD UNIQUE INDEX uk_merchant_product_active
        (merchant_id, product_name, active_name_guard);
