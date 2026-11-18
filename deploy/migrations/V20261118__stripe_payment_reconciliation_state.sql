-- COMPLETE is retained for legacy payment rows written before RECONCILED was introduced.
ALTER TABLE order_stripe_payments
    DROP CHECK chk_stripe_reconciliation_state,
    ADD CONSTRAINT chk_stripe_payment_reconciliation_state
        CHECK (reconciliation_state IN ('PENDING', 'COMPLETE', 'REVIEW', 'RECONCILED'));
