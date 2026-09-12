-- Existing databases are not changed by CREATE TABLE IF NOT EXISTS in schema.sql.
-- This consolidated migration creates the complete cache invalidation task table;
-- no follow-up retry-field migration is required.
-- Run this once when deploying the persistent cache invalidation task change.
CREATE TABLE IF NOT EXISTS `cache_invalidation_task` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `cache_key` varchar(255) NOT NULL,
    `status` tinyint NOT NULL DEFAULT 0 COMMENT '任务状态: 0-PENDING, 1-SUCCESS, 2-FAILED',
    `retry_count` int NOT NULL DEFAULT 0 COMMENT '已经失败的次数',
    `next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最早再次执行时间',
    `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_status_created_time` (`status`, `created_time`)
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '缓存失效任务表';
