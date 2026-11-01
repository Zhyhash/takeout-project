-- Read-only business-data verification. Change only this session variable to your test order ID.
SET @stripe_order_id = 0;

SELECT o.id AS order_id,
       o.order_no,
       o.status AS order_status,
       o.status = 2 AS is_paid,
       o.pay_time,
       o.total_amount AS order_cny_yuan,
       p.payment_intent_id,
       p.currency,
       p.amount_minor AS stripe_cny_fen,
       p.amount_minor = o.total_amount * 100 AS amount_matches,
       p.stripe_status,
       (SELECT COUNT(*)
        FROM stripe_payment_events e
        WHERE e.payment_intent_id = p.payment_intent_id
          AND e.event_type = 'payment_intent.succeeded') AS succeeded_webhook_events
FROM orders o
LEFT JOIN order_stripe_payments p ON p.order_id = o.id
WHERE o.id = @stripe_order_id;

SELECT e.event_id, e.event_type, e.payment_intent_id, e.create_time
FROM stripe_payment_events e
JOIN order_stripe_payments p ON p.payment_intent_id = e.payment_intent_id
WHERE p.order_id = @stripe_order_id
ORDER BY e.create_time, e.event_id;
