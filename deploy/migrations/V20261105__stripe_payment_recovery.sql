-- A PAYING order without a payment record cannot safely be re-created. Stop deployment for reconciliation.
CREATE TEMPORARY TABLE stripe_recovery_preflight (
    orphan_count bigint NOT NULL,
    CONSTRAINT chk_no_orphan_paying_orders CHECK (orphan_count = 0)
);
INSERT INTO stripe_recovery_preflight
SELECT COUNT(*) FROM orders o LEFT JOIN order_stripe_payments p ON p.order_id = o.id
WHERE o.status = 1 AND p.order_id IS NULL;
DROP TEMPORARY TABLE stripe_recovery_preflight;

-- New payments persist eligibility and recovery state BEFORE requesting Stripe.
SET @stripe_recovery_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'payment_started_at') = 0,
    'ALTER TABLE orders ADD COLUMN payment_started_at datetime NULL DEFAULT NULL
    COMMENT ''Payment eligibility acquired, pay_time remains success time only''', 'SELECT 1');
PREPARE stripe_recovery_statement FROM @stripe_recovery_ddl;
EXECUTE stripe_recovery_statement;
DEALLOCATE PREPARE stripe_recovery_statement;

SET @stripe_recovery_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'order_stripe_payments' AND column_name = 'reconciliation_state') = 0,
    'ALTER TABLE order_stripe_payments
    ADD COLUMN reconciliation_state varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''PENDING'',
    ADD COLUMN idempotency_expires_at datetime NULL DEFAULT NULL,
    ADD COLUMN retry_count int NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at datetime NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    ADD COLUMN lease_until datetime NULL DEFAULT NULL,
    ADD COLUMN last_error varchar(128) NULL DEFAULT NULL,
    ADD CONSTRAINT chk_stripe_reconciliation_state CHECK (reconciliation_state IN (''PENDING'', ''COMPLETE'', ''REVIEW'')),
    ADD CONSTRAINT chk_stripe_retry_count CHECK (retry_count >= 0),
    ADD KEY idx_stripe_recovery_due (reconciliation_state, next_retry_at, order_id)', 'SELECT 1');
PREPARE stripe_recovery_statement FROM @stripe_recovery_ddl;
EXECUTE stripe_recovery_statement;
DEALLOCATE PREPARE stripe_recovery_statement;

UPDATE order_stripe_payments SET idempotency_expires_at = DATE_ADD(create_time, INTERVAL 23 HOUR);

-- Legacy WAIT_PAY + payment record may already have a remote intent. Protect it from timeout cancellation.
-- Do not mark it PAID or return stock in a migration: trusted snapshots and normal business transactions do that.
UPDATE orders o JOIN order_stripe_payments p ON p.order_id = o.id
SET o.status = 1, o.payment_started_at = p.create_time, o.update_time = o.update_time
WHERE o.status = 0;

UPDATE orders o JOIN order_stripe_payments p ON p.order_id = o.id
SET o.payment_started_at = p.create_time, o.update_time = o.update_time
WHERE o.status = 1 AND o.payment_started_at IS NULL;

UPDATE order_stripe_payments p JOIN orders o ON o.id = p.order_id
SET p.reconciliation_state = 'COMPLETE', p.next_retry_at = NULL
WHERE (p.stripe_status = 'succeeded' AND o.status IN (2, 3, 5, 6, 7, 8))
   OR (p.stripe_status = 'canceled' AND o.status = 4);

UPDATE order_stripe_payments
SET reconciliation_state = 'REVIEW', next_retry_at = NULL,
    last_error = 'CREATE_RESULT_UNKNOWN_IDEMPOTENCY_EXPIRED'
WHERE reconciliation_state = 'PENDING' AND payment_intent_id IS NULL
  AND idempotency_expires_at <= CURRENT_TIMESTAMP;

