-- Local takeout database only: one payment-test order for demo_user.
-- Execute as a single MySQL batch without --force. Any assertion error must
-- close the connection so that the open transaction rolls back.
-- The fixed request_id prevents this fixture from creating a second order.
-- This prepares order data only; it does not create or confirm a Stripe payment.

SET SESSION time_zone = '+08:00';
SET SESSION innodb_lock_wait_timeout = 5;

CREATE TEMPORARY TABLE stripe_test_order_guard (
    ok TINYINT NOT NULL,
    CONSTRAINT chk_stripe_test_order_guard CHECK (ok = 1)
) ENGINE = InnoDB;

START TRANSACTION;

INSERT INTO stripe_test_order_guard VALUES (IF(DATABASE() = 'takeout', 1, 0));

SET @fixture_request_id = 'stripe-local-demo-user-20261030-01';
SET @fixture_user_id = NULL;
SET @fixture_merchant_id = NULL;
SET @fixture_product_id = NULL;

SELECT id, phone INTO @fixture_user_id, @fixture_receiver_phone
FROM `user`
WHERE username = 'demo_user' AND status = 1
FOR SHARE;

INSERT INTO stripe_test_order_guard VALUES (IF(@fixture_user_id = 1, 1, 0));

SELECT COUNT(*) INTO @fixture_existing_orders
FROM orders
WHERE user_id = @fixture_user_id AND request_id = @fixture_request_id;

INSERT INTO stripe_test_order_guard VALUES (IF(@fixture_existing_orders = 0, 1, 0));

SELECT id, merchant_name INTO @fixture_merchant_id, @fixture_merchant_name
FROM merchant
WHERE id = 1 AND status = 0
FOR SHARE;

INSERT INTO stripe_test_order_guard VALUES (IF(@fixture_merchant_id = 1, 1, 0));

SELECT id, product_name, price, stock, stock_version, image_url
INTO @fixture_product_id, @fixture_product_name, @fixture_price,
     @fixture_stock_before, @fixture_stock_version, @fixture_product_picture
FROM product
WHERE id = 1 AND merchant_id = @fixture_merchant_id
  AND is_deleted = 0 AND status = 0 AND stock > 1 AND price = 25.80
  AND category_id IN (
      SELECT id FROM category WHERE merchant_id = @fixture_merchant_id AND status = 0
  )
FOR UPDATE;

INSERT INTO stripe_test_order_guard VALUES (IF(@fixture_product_id = 1, 1, 0));

-- Match ProductMapper.decreaseStock: preserve stock_version and advance version.
-- stock > 1 above avoids crossing the cached in-stock/out-of-stock boundary.
UPDATE product
SET status = CASE WHEN stock = 1 THEN 2 ELSE status END,
    stock = stock - 1,
    version = version + 1
WHERE id = @fixture_product_id AND stock_version = @fixture_stock_version
  AND stock = @fixture_stock_before AND stock > 1
  AND is_deleted = 0 AND status = 0;

INSERT INTO stripe_test_order_guard VALUES (IF(ROW_COUNT() = 1, 1, 0));

SET @fixture_delivery_fee = 5.00;
SET @fixture_receiver_name = 'demo_user';
SET @fixture_receiver_address = 'Stripe sandbox payment test only (no delivery)';
SET @fixture_remark = '[Stripe sandbox fixture] Direct SQL order; no delivery validation.';
SET @fixture_created_at = NOW();
SET @fixture_order_no = CONCAT('STP', DATE_FORMAT(@fixture_created_at, '%Y%m%d%H%i%s'),
                               LEFT(REPLACE(UUID(), '-', ''), 8));
SET @fixture_request_hash = SHA2(CONCAT(
    CHAR_LENGTH(@fixture_receiver_name), ':', @fixture_receiver_name,
    CHAR_LENGTH(@fixture_receiver_phone), ':', @fixture_receiver_phone,
    CHAR_LENGTH(@fixture_receiver_address), ':', @fixture_receiver_address,
    CHAR_LENGTH(@fixture_remark), ':', @fixture_remark,
    'N:N:'
), 256);

INSERT INTO orders (
    order_no, user_id, request_id, request_hash, merchant_id, merchant_name,
    total_amount, delivery_fee, status, cancel_source,
    receiver_name, receiver_phone, receiver_address, remark,
    create_time, timeout_cancel_available_at, update_time,
    finish_time, original_amount, discount_amount, pay_time, merchant_seen
) VALUES (
    @fixture_order_no, @fixture_user_id, @fixture_request_id, @fixture_request_hash,
    @fixture_merchant_id, @fixture_merchant_name,
    @fixture_price + @fixture_delivery_fee, @fixture_delivery_fee, 0, NULL,
    @fixture_receiver_name, @fixture_receiver_phone, @fixture_receiver_address, @fixture_remark,
    @fixture_created_at, DATE_ADD(@fixture_created_at, INTERVAL 30 MINUTE), @fixture_created_at,
    NULL, @fixture_price, 0.00, NULL, 0
);

SET @fixture_order_id = LAST_INSERT_ID();

INSERT INTO order_item (
    order_id, product_id, product_name, product_price,
    quantity, stock_version, subtotal, product_picture
) VALUES (
    @fixture_order_id, @fixture_product_id, @fixture_product_name, @fixture_price,
    1, @fixture_stock_version, @fixture_price, @fixture_product_picture
);

INSERT INTO stripe_test_order_guard
SELECT IF(COUNT(*) = 1, 1, 0)
FROM orders o
JOIN order_item i ON i.order_id = o.id
JOIN product p ON p.id = i.product_id
WHERE o.id = @fixture_order_id AND o.user_id = @fixture_user_id
  AND o.status = 0 AND o.pay_time IS NULL
  AND i.quantity = 1 AND i.stock_version = p.stock_version
  AND i.subtotal = i.product_price
  AND o.original_amount = i.subtotal AND o.discount_amount = 0.00
  AND o.total_amount = o.original_amount + o.delivery_fee - o.discount_amount
  AND o.total_amount = 30.80
  AND p.stock = @fixture_stock_before - 1
  AND NOT EXISTS (SELECT 1 FROM order_stripe_payments s WHERE s.order_id = o.id);

COMMIT;

SELECT o.id AS order_id, u.username, o.status AS order_status,
       o.total_amount AS cny_yuan, o.total_amount * 100 AS cny_fen,
       o.create_time, o.timeout_cancel_available_at AS expires_at,
       i.product_id, i.quantity, i.stock_version,
       @fixture_stock_before AS stock_before, p.stock AS stock_after,
       o.pay_time
FROM orders o JOIN `user` u ON u.id = o.user_id
JOIN order_item i ON i.order_id = o.id
JOIN product p ON p.id = i.product_id
WHERE o.id = @fixture_order_id;

DROP TEMPORARY TABLE stripe_test_order_guard;
