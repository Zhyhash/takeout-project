-- 记录 Stripe 退款失败原因（refund.failure_reason），供后续按原因判断是否值得自动重试。
-- 失败原因与状态分开存储：stripe_status=failed 表示"钱没退成"，failure_reason 说明"为什么没退成"。
SET @stripe_refund_failure_reason_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'order_stripe_refunds'
       AND column_name = 'failure_reason') = 0,
    'ALTER TABLE order_stripe_refunds ADD COLUMN failure_reason varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL',
    'SELECT 1');
PREPARE stripe_refund_failure_reason_statement FROM @stripe_refund_failure_reason_ddl;
EXECUTE stripe_refund_failure_reason_statement;
DEALLOCATE PREPARE stripe_refund_failure_reason_statement;

SET @stripe_refund_failed_index_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'order_stripe_refunds'
       AND index_name = 'idx_order_stripe_refunds_status') = 0,
    'ALTER TABLE order_stripe_refunds ADD KEY idx_order_stripe_refunds_status (stripe_status)',
    'SELECT 1');
PREPARE stripe_refund_failed_index_statement FROM @stripe_refund_failed_index_ddl;
EXECUTE stripe_refund_failed_index_statement;
DEALLOCATE PREPARE stripe_refund_failed_index_statement;
