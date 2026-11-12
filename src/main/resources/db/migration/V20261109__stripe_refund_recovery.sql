-- Bound automatic retries when local and remote Stripe refund states conflict.
-- NULL means a PENDING refund is immediately eligible for recovery.
SET @stripe_refund_retry_count_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'order_stripe_refunds'
       AND column_name = 'retry_count') = 0,
    'ALTER TABLE order_stripe_refunds ADD COLUMN retry_count int NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stripe_refund_retry_count_statement FROM @stripe_refund_retry_count_ddl;
EXECUTE stripe_refund_retry_count_statement;
DEALLOCATE PREPARE stripe_refund_retry_count_statement;

SET @stripe_refund_next_retry_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'order_stripe_refunds'
       AND column_name = 'next_retry_at') = 0,
    'ALTER TABLE order_stripe_refunds ADD COLUMN next_retry_at datetime NULL DEFAULT NULL',
    'SELECT 1');
PREPARE stripe_refund_next_retry_statement FROM @stripe_refund_next_retry_ddl;
EXECUTE stripe_refund_next_retry_statement;
DEALLOCATE PREPARE stripe_refund_next_retry_statement;
