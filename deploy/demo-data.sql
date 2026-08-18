-- Tokeout local demo data for MySQL 8.
--
-- Prerequisite: execute deploy/schema.sql first.
-- All demo accounts use password: Demo@123456
--
-- This script is for local development only and can be executed repeatedly.
-- Re-execution resets the demo cart, orders, order items and delivery tasks to
-- the states below, but does not clear normal business data.

SET NAMES utf8mb4;
USE takeout;

START TRANSACTION;

SET @demo_password_hash =
    '$2a$10$xVF8kyJNfvc7S2tOM89IV.lUcubxa5K8xt2yiwOgKntIJ1DjxS7e2';

-- ---------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------

INSERT INTO `user` (
    phone, password, nickname, email, status,
    create_time, update_time, username
)
VALUES (
    '13900000001', @demo_password_hash, '演示用户',
    'demo.user@example.com', 1, NOW(), NOW(), 'demo_user'
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    phone = '13900000001',
    password = @demo_password_hash,
    nickname = '演示用户',
    email = 'demo.user@example.com',
    status = 1,
    update_time = NOW();
SET @demo_user_id = LAST_INSERT_ID();

INSERT INTO merchant (
    username, phone, password, address, status,
    create_time, merchant_name, picture, merchant_description,
    opening_time, closing_time, version
)
VALUES (
    'demo_merchant', '13900000002', @demo_password_hash,
    '上海市浦东新区演示路 100 号', 0, NOW(),
    '演示餐厅', '/images/default-product.svg',
    '用于本地接口调试的演示商家',
    '08:00:00', '23:00:00', 0
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    phone = '13900000002',
    password = @demo_password_hash,
    address = '上海市浦东新区演示路 100 号',
    status = 0,
    merchant_name = '演示餐厅',
    picture = '/images/default-product.svg',
    merchant_description = '用于本地接口调试的演示商家',
    opening_time = '08:00:00',
    closing_time = '23:00:00',
    version = 0;
SET @demo_merchant_id = LAST_INSERT_ID();

INSERT INTO rider (
    name, phone, password, status,
    create_time, update_time, is_delete
)
VALUES (
    'demo_rider', '13900000003', @demo_password_hash, 1,
    NOW(), NOW(), 0
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    phone = '13900000003',
    password = @demo_password_hash,
    status = 1,
    update_time = NOW(),
    is_delete = 0;
SET @demo_rider_id = LAST_INSERT_ID();

-- ---------------------------------------------------------------------------
-- Categories and products
-- category.status: 0 = ACTIVE
-- category.is_default: 0 = DEFAULT, 1 = CLASSIFICATION
-- product.status: 0 = ON_SALE
-- ---------------------------------------------------------------------------

INSERT INTO category (merchant_id, category_name, status, is_default)
VALUES (@demo_merchant_id, '默认分类', 0, 0)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    status = 0,
    is_default = 0;
SET @demo_default_category_id = LAST_INSERT_ID();

INSERT INTO category (merchant_id, category_name, status, is_default)
VALUES (@demo_merchant_id, '演示热销', 0, 1)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    status = 0,
    is_default = 1;
SET @demo_hot_category_id = LAST_INSERT_ID();

INSERT INTO product (
    category_id, product_name, image_url, price, stock,
    merchant_id, is_deleted, status, description,
    create_time, update_time, version
)
VALUES (
    @demo_hot_category_id, '演示宫保鸡丁',
    '/images/default-product.svg', 25.80, 100,
    @demo_merchant_id, 0, 0, '微辣，适合调试订单主流程',
    NOW(), NOW(), 0
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    category_id = @demo_hot_category_id,
    image_url = '/images/default-product.svg',
    price = 25.80,
    stock = 100,
    is_deleted = 0,
    status = 0,
    description = '微辣，适合调试订单主流程',
    update_time = NOW(),
    version = 0;
SET @demo_product_main_id = LAST_INSERT_ID();

INSERT INTO product (
    category_id, product_name, image_url, price, stock,
    merchant_id, is_deleted, status, description,
    create_time, update_time, version
)
VALUES (
    @demo_default_category_id, '演示米饭',
    '/images/default-product.svg', 3.00, 200,
    @demo_merchant_id, 0, 0, '演示主食',
    NOW(), NOW(), 0
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    category_id = @demo_default_category_id,
    image_url = '/images/default-product.svg',
    price = 3.00,
    stock = 200,
    is_deleted = 0,
    status = 0,
    description = '演示主食',
    update_time = NOW(),
    version = 0;
SET @demo_product_rice_id = LAST_INSERT_ID();

INSERT INTO product (
    category_id, product_name, image_url, price, stock,
    merchant_id, is_deleted, status, description,
    create_time, update_time, version
)
VALUES (
    @demo_hot_category_id, '演示可乐',
    '/images/default-product.svg', 5.00, 100,
    @demo_merchant_id, 0, 0, '购物车预置商品，可直接用于创建新订单',
    NOW(), NOW(), 0
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    category_id = @demo_hot_category_id,
    image_url = '/images/default-product.svg',
    price = 5.00,
    stock = 100,
    is_deleted = 0,
    status = 0,
    description = '购物车预置商品，可直接用于创建新订单',
    update_time = NOW(),
    version = 0;
SET @demo_product_drink_id = LAST_INSERT_ID();

-- ---------------------------------------------------------------------------
-- Cart
-- The demo user starts with two drinks and can call POST /order directly
-- after logging in and supplying a new requestId.
-- ---------------------------------------------------------------------------

INSERT INTO cart (
    user_id, product_id, product_name, quantity, price,
    create_time, update_time, merchant_id, product_image, version
)
VALUES (
    @demo_user_id, @demo_product_drink_id, '演示可乐', 2, 5.00,
    NOW(), NOW(), @demo_merchant_id, '/images/default-product.svg', 0
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    product_name = '演示可乐',
    quantity = 2,
    price = 5.00,
    update_time = NOW(),
    merchant_id = @demo_merchant_id,
    product_image = '/images/default-product.svg',
    version = 0;

-- ---------------------------------------------------------------------------
-- Orders
-- order.status:
--   2 = PAID, 6 = READY, 7 = DELIVERING, 8 = DELIVERED
--
-- These orders provide entry points for merchant accept, rider claim,
-- rider complete and user confirm operations.
-- ---------------------------------------------------------------------------

INSERT INTO orders (
    order_no, user_id, request_id, merchant_id, merchant_name,
    total_amount, status, receiver_name, receiver_phone,
    receiver_address, remark, create_time, update_time,
    finish_time, original_amount, discount_amount, pay_time
)
VALUES (
    'DEMO-PAID-0001', @demo_user_id, 'demo-paid-order',
    @demo_merchant_id, '演示餐厅', 28.80, 2,
    '演示用户', '13900000001', '上海市浦东新区调试路 1 号',
    '等待商家接单', DATE_SUB(NOW(), INTERVAL 20 MINUTE),
    DATE_SUB(NOW(), INTERVAL 19 MINUTE), NULL,
    28.80, 0.00, DATE_SUB(NOW(), INTERVAL 19 MINUTE)
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    merchant_id = @demo_merchant_id,
    merchant_name = '演示餐厅',
    total_amount = 28.80,
    status = 2,
    receiver_name = '演示用户',
    receiver_phone = '13900000001',
    receiver_address = '上海市浦东新区调试路 1 号',
    remark = '等待商家接单',
    create_time = DATE_SUB(NOW(), INTERVAL 20 MINUTE),
    update_time = DATE_SUB(NOW(), INTERVAL 19 MINUTE),
    finish_time = NULL,
    original_amount = 28.80,
    discount_amount = 0.00,
    pay_time = DATE_SUB(NOW(), INTERVAL 19 MINUTE);
SET @demo_paid_order_id = LAST_INSERT_ID();

INSERT INTO orders (
    order_no, user_id, request_id, merchant_id, merchant_name,
    total_amount, status, receiver_name, receiver_phone,
    receiver_address, remark, create_time, update_time,
    finish_time, original_amount, discount_amount, pay_time
)
VALUES (
    'DEMO-READY-0001', @demo_user_id, 'demo-ready-order',
    @demo_merchant_id, '演示餐厅', 25.80, 6,
    '演示用户', '13900000001', '上海市浦东新区调试路 2 号',
    '等待骑手抢单', DATE_SUB(NOW(), INTERVAL 15 MINUTE),
    DATE_SUB(NOW(), INTERVAL 12 MINUTE), NULL,
    25.80, 0.00, DATE_SUB(NOW(), INTERVAL 14 MINUTE)
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    merchant_id = @demo_merchant_id,
    merchant_name = '演示餐厅',
    total_amount = 25.80,
    status = 6,
    receiver_name = '演示用户',
    receiver_phone = '13900000001',
    receiver_address = '上海市浦东新区调试路 2 号',
    remark = '等待骑手抢单',
    create_time = DATE_SUB(NOW(), INTERVAL 15 MINUTE),
    update_time = DATE_SUB(NOW(), INTERVAL 12 MINUTE),
    finish_time = NULL,
    original_amount = 25.80,
    discount_amount = 0.00,
    pay_time = DATE_SUB(NOW(), INTERVAL 14 MINUTE);
SET @demo_ready_order_id = LAST_INSERT_ID();

INSERT INTO orders (
    order_no, user_id, request_id, merchant_id, merchant_name,
    total_amount, status, receiver_name, receiver_phone,
    receiver_address, remark, create_time, update_time,
    finish_time, original_amount, discount_amount, pay_time
)
VALUES (
    'DEMO-DELIVERING-0001', @demo_user_id, 'demo-delivering-order',
    @demo_merchant_id, '演示餐厅', 28.80, 7,
    '演示用户', '13900000001', '上海市浦东新区调试路 3 号',
    '由演示骑手配送中', DATE_SUB(NOW(), INTERVAL 10 MINUTE),
    DATE_SUB(NOW(), INTERVAL 5 MINUTE), NULL,
    28.80, 0.00, DATE_SUB(NOW(), INTERVAL 9 MINUTE)
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    merchant_id = @demo_merchant_id,
    merchant_name = '演示餐厅',
    total_amount = 28.80,
    status = 7,
    receiver_name = '演示用户',
    receiver_phone = '13900000001',
    receiver_address = '上海市浦东新区调试路 3 号',
    remark = '由演示骑手配送中',
    create_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE),
    update_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE),
    finish_time = NULL,
    original_amount = 28.80,
    discount_amount = 0.00,
    pay_time = DATE_SUB(NOW(), INTERVAL 9 MINUTE);
SET @demo_delivering_order_id = LAST_INSERT_ID();

INSERT INTO orders (
    order_no, user_id, request_id, merchant_id, merchant_name,
    total_amount, status, receiver_name, receiver_phone,
    receiver_address, remark, create_time, update_time,
    finish_time, original_amount, discount_amount, pay_time
)
VALUES (
    'DEMO-DELIVERED-0001', @demo_user_id, 'demo-delivered-order',
    @demo_merchant_id, '演示餐厅', 10.00, 8,
    '演示用户', '13900000001', '上海市浦东新区调试路 4 号',
    '等待用户确认收货', DATE_SUB(NOW(), INTERVAL 8 MINUTE),
    DATE_SUB(NOW(), INTERVAL 1 MINUTE), NULL,
    10.00, 0.00, DATE_SUB(NOW(), INTERVAL 7 MINUTE)
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    merchant_id = @demo_merchant_id,
    merchant_name = '演示餐厅',
    total_amount = 10.00,
    status = 8,
    receiver_name = '演示用户',
    receiver_phone = '13900000001',
    receiver_address = '上海市浦东新区调试路 4 号',
    remark = '等待用户确认收货',
    create_time = DATE_SUB(NOW(), INTERVAL 8 MINUTE),
    update_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE),
    finish_time = NULL,
    original_amount = 10.00,
    discount_amount = 0.00,
    pay_time = DATE_SUB(NOW(), INTERVAL 7 MINUTE);
SET @demo_delivered_order_id = LAST_INSERT_ID();

-- order_item has no business unique key. Delete only these demo orders' items
-- before recreating their snapshots so re-execution stays deterministic.
DELETE FROM order_item
WHERE order_id IN (
    @demo_paid_order_id,
    @demo_ready_order_id,
    @demo_delivering_order_id,
    @demo_delivered_order_id
);

INSERT INTO order_item (
    order_id, product_id, product_name, product_price,
    quantity, subtotal, product_picture
)
VALUES
    (
        @demo_paid_order_id, @demo_product_main_id, '演示宫保鸡丁',
        25.80, 1, 25.80, '/images/default-product.svg'
    ),
    (
        @demo_paid_order_id, @demo_product_rice_id, '演示米饭',
        3.00, 1, 3.00, '/images/default-product.svg'
    ),
    (
        @demo_ready_order_id, @demo_product_main_id, '演示宫保鸡丁',
        25.80, 1, 25.80, '/images/default-product.svg'
    ),
    (
        @demo_delivering_order_id, @demo_product_main_id, '演示宫保鸡丁',
        25.80, 1, 25.80, '/images/default-product.svg'
    ),
    (
        @demo_delivering_order_id, @demo_product_rice_id, '演示米饭',
        3.00, 1, 3.00, '/images/default-product.svg'
    ),
    (
        @demo_delivered_order_id, @demo_product_drink_id, '演示可乐',
        5.00, 2, 10.00, '/images/default-product.svg'
    );

-- ---------------------------------------------------------------------------
-- Delivery tasks
-- delivery_task.status:
--   0 = WAIT_ASSIGN, 1 = DELIVERING, 2 = COMPLETED
-- ---------------------------------------------------------------------------

INSERT INTO delivery_task (
    order_id, rider_id, merchant_name, delivery_reward,
    receiver_name, status, create_time, accepted_time,
    delivered_time, update_time, receiver_phone, receiver_address,
    merchant_address, merchant_phone
)
VALUES (
    @demo_ready_order_id, NULL, '演示餐厅', 5.00,
    '演示用户', 0, DATE_SUB(NOW(), INTERVAL 12 MINUTE), NULL,
    NULL, DATE_SUB(NOW(), INTERVAL 12 MINUTE),
    '13900000001', '上海市浦东新区调试路 2 号',
    '上海市浦东新区演示路 100 号', '13900000002'
)
ON DUPLICATE KEY UPDATE
    rider_id = NULL,
    merchant_name = '演示餐厅',
    delivery_reward = 5.00,
    receiver_name = '演示用户',
    status = 0,
    create_time = DATE_SUB(NOW(), INTERVAL 12 MINUTE),
    accepted_time = NULL,
    delivered_time = NULL,
    update_time = DATE_SUB(NOW(), INTERVAL 12 MINUTE),
    receiver_phone = '13900000001',
    receiver_address = '上海市浦东新区调试路 2 号',
    merchant_address = '上海市浦东新区演示路 100 号',
    merchant_phone = '13900000002';

INSERT INTO delivery_task (
    order_id, rider_id, merchant_name, delivery_reward,
    receiver_name, status, create_time, accepted_time,
    delivered_time, update_time, receiver_phone, receiver_address,
    merchant_address, merchant_phone
)
VALUES (
    @demo_delivering_order_id, @demo_rider_id, '演示餐厅', 5.00,
    '演示用户', 1, DATE_SUB(NOW(), INTERVAL 7 MINUTE),
    DATE_SUB(NOW(), INTERVAL 5 MINUTE), NULL,
    DATE_SUB(NOW(), INTERVAL 5 MINUTE),
    '13900000001', '上海市浦东新区调试路 3 号',
    '上海市浦东新区演示路 100 号', '13900000002'
)
ON DUPLICATE KEY UPDATE
    rider_id = @demo_rider_id,
    merchant_name = '演示餐厅',
    delivery_reward = 5.00,
    receiver_name = '演示用户',
    status = 1,
    create_time = DATE_SUB(NOW(), INTERVAL 7 MINUTE),
    accepted_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE),
    delivered_time = NULL,
    update_time = DATE_SUB(NOW(), INTERVAL 5 MINUTE),
    receiver_phone = '13900000001',
    receiver_address = '上海市浦东新区调试路 3 号',
    merchant_address = '上海市浦东新区演示路 100 号',
    merchant_phone = '13900000002';

INSERT INTO delivery_task (
    order_id, rider_id, merchant_name, delivery_reward,
    receiver_name, status, create_time, accepted_time,
    delivered_time, update_time, receiver_phone, receiver_address,
    merchant_address, merchant_phone
)
VALUES (
    @demo_delivered_order_id, @demo_rider_id, '演示餐厅', 5.00,
    '演示用户', 2, DATE_SUB(NOW(), INTERVAL 6 MINUTE),
    DATE_SUB(NOW(), INTERVAL 4 MINUTE),
    DATE_SUB(NOW(), INTERVAL 1 MINUTE),
    DATE_SUB(NOW(), INTERVAL 1 MINUTE),
    '13900000001', '上海市浦东新区调试路 4 号',
    '上海市浦东新区演示路 100 号', '13900000002'
)
ON DUPLICATE KEY UPDATE
    rider_id = @demo_rider_id,
    merchant_name = '演示餐厅',
    delivery_reward = 5.00,
    receiver_name = '演示用户',
    status = 2,
    create_time = DATE_SUB(NOW(), INTERVAL 6 MINUTE),
    accepted_time = DATE_SUB(NOW(), INTERVAL 4 MINUTE),
    delivered_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE),
    update_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE),
    receiver_phone = '13900000001',
    receiver_address = '上海市浦东新区调试路 4 号',
    merchant_address = '上海市浦东新区演示路 100 号',
    merchant_phone = '13900000002';

COMMIT;

SELECT
    'demo_user' AS login_name,
    'user' AS account_role,
    'Demo@123456' AS login_password
UNION ALL
SELECT 'demo_merchant', 'merchant', 'Demo@123456'
UNION ALL
SELECT 'demo_rider', 'rider', 'Demo@123456';

SELECT
    id AS order_id,
    order_no,
    request_id,
    status,
    remark
FROM orders
WHERE id IN (
    @demo_paid_order_id,
    @demo_ready_order_id,
    @demo_delivering_order_id,
    @demo_delivered_order_id
)
ORDER BY id;

SELECT
    id AS delivery_task_id,
    order_id,
    rider_id,
    status
FROM delivery_task
WHERE order_id IN (
    @demo_ready_order_id,
    @demo_delivering_order_id,
    @demo_delivered_order_id
)
ORDER BY id;

SELECT
    id AS cart_item_id,
    product_id,
    quantity
FROM cart
WHERE user_id = @demo_user_id
  AND product_id = @demo_product_drink_id;
