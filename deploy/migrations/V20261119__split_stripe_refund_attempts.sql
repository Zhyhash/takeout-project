-- Preserve the remote fields of each existing refund as its first attempt.
CREATE TABLE order_stripe_refund_attempts (
    id bigint NOT NULL AUTO_INCREMENT,
    order_id bigint NOT NULL,
    refund_id varchar(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    idempotency_key varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stripe_status varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    reconciliation_state varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_reason varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    retry_count int NOT NULL DEFAULT 0,
    next_retry_at datetime DEFAULT NULL,
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_stripe_refund_attempt_refund_id (refund_id),
    UNIQUE KEY uk_order_stripe_refund_attempt_idempotency (idempotency_key),
    KEY idx_order_stripe_refund_attempt_order (order_id),
    KEY idx_order_stripe_refund_attempt_status (stripe_status),
    CONSTRAINT fk_order_stripe_refund_attempt_order
        FOREIGN KEY (order_id) REFERENCES order_stripe_refunds (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE order_stripe_refunds
    ADD COLUMN status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' AFTER currency,
    ADD CONSTRAINT chk_order_stripe_refund_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'REVIEW'));

INSERT INTO order_stripe_refund_attempts (
    order_id, refund_id, idempotency_key, stripe_status,
    reconciliation_state, failure_reason, retry_count, next_retry_at, create_time, update_time
)
SELECT order_id, refund_id, idempotency_key, stripe_status,
       reconciliation_state, failure_reason, retry_count, next_retry_at, create_time, update_time
FROM order_stripe_refunds;

-- Failed and canceled refunds currently require a manual decision before retrying.
UPDATE order_stripe_refunds
SET status = CASE
        WHEN reconciliation_state = 'REVIEW' OR stripe_status IN ('failed', 'canceled') THEN 'REVIEW'
        WHEN stripe_status = 'succeeded' THEN 'SUCCEEDED'
        ELSE 'PENDING'
    END,
    update_time = update_time;

ALTER TABLE order_stripe_refunds
    DROP INDEX uk_order_stripe_refund_id,
    DROP INDEX uk_order_stripe_refund_idempotency,
    DROP INDEX idx_order_stripe_refunds_status,
    DROP COLUMN refund_id,
    DROP COLUMN idempotency_key,
    DROP COLUMN stripe_status,
    DROP COLUMN reconciliation_state,
    DROP COLUMN failure_reason,
    DROP COLUMN retry_count,
    DROP COLUMN next_retry_at;
