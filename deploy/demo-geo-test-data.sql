-- Tokeout local GEO / three-end demo seed (MySQL 8+).
--
-- Purpose:
--   Minimal, repeatable base data for manually verifying:
--   order -> pay -> merchant accept -> ready -> WAITING delivery task
--   -> Redis GEO -> rider browser geolocation -> nearby list -> claim.
--
-- Scope:
--   Accounts, merchant profile, categories and products only.
--   This script NEVER inserts/updates/deletes orders or delivery_task rows and
--   NEVER writes Redis. Orders/tasks must be produced by the real application
--   flow so that pickup coordinates are snapshotted and GEO is populated by
--   the existing business code.
--
-- Safety:
--   Local development / test database only. It uses fixed demo credentials.
--   Requires the schema to be at Flyway V20261027 (merchant longitude/latitude,
--   delivery_task pickup_longitude/pickup_latitude).
--
-- Repeatability:
--   Every write is INSERT ... ON DUPLICATE KEY UPDATE. Running it again resets
--   only the demo accounts/profile/catalog below. It does not delete other rows.
--
-- Target database:
--   The script explicitly uses `takeout`. Confirm DB_URL/port before running.

SET NAMES utf8mb4;
USE `takeout`;

START TRANSACTION;

-- BCrypt hashes for the plaintext demo password 12345678.
-- Generated with the project's BCrypt.encode(...), cost 10, one salt per role.
SET @demo_user_password =
    '$2a$10$e1GU/yqmlusyykW5XAy2O.lWV3aQNcCOnH6REXE2cnek5Ya0kZdgW';
SET @demo_merchant_password =
    '$2a$10$k.kKRMM/xRU7AEgQy0Qd5uCe7we1aJhDIQ3UnZ2T/l7Fch2ymjq2e';
SET @demo_rider_password =
    '$2a$10$4Vnt47nmP.my49zL2A7v5.BFs20nxkRSbgQUcc9/BFL5sGbOeJEa6';

-- ---------------------------------------------------------------------------
-- Three demo accounts. Login fields are the real project login fields:
--   user      -> user.username
--   merchant  -> merchant.username
--   rider     -> rider.name
-- ---------------------------------------------------------------------------

INSERT INTO `user` (
    phone, password, nickname, email, status,
    create_time, update_time, username
)
VALUES (
    '13900000001', @demo_user_password, '演示用户',
    'demo.user@example.com', 1, NOW(), NOW(), 'demo_user'
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    phone = '13900000001',
    password = @demo_user_password,
    nickname = '演示用户',
    email = 'demo.user@example.com',
    status = 1,
    update_time = NOW();
SET @demo_user_id = LAST_INSERT_ID();

INSERT INTO merchant (
    username, phone, password, address, longitude, latitude, status,
    create_time, merchant_name, picture, merchant_description,
    opening_time, closing_time
)
VALUES (
    'demo_merchant', '13900000002', @demo_merchant_password,
    '重庆市南岸区崇文路2号 重庆邮电大学（本地演示地址）',
    106.6078000, 29.5319000, 0, NOW(),
    '重邮演示餐厅', '/images/default-product.svg',
    '重庆邮电大学附近的三端联调演示商家',
    '08:00:00', '23:00:00'
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    phone = '13900000002',
    password = @demo_merchant_password,
    address = '重庆市南岸区崇文路2号 重庆邮电大学（本地演示地址）',
    longitude = 106.6078000,
    latitude = 29.5319000,
    status = 0,
    merchant_name = '重邮演示餐厅',
    picture = '/images/default-product.svg',
    merchant_description = '重庆邮电大学附近的三端联调演示商家',
    opening_time = '08:00:00',
    closing_time = '23:00:00';
SET @demo_merchant_id = LAST_INSERT_ID();

INSERT INTO rider (
    name, phone, password, status,
    create_time, update_time, is_delete
)
VALUES (
    'demo_rider', '13900000003', @demo_rider_password, 1,
    NOW(), NOW(), 0
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    phone = '13900000003',
    password = @demo_rider_password,
    status = 1,
    update_time = NOW(),
    is_delete = 0;
SET @demo_rider_id = LAST_INSERT_ID();

-- The real registration flow creates this header. Add it for the demo user
-- without touching cart rows: the manual tester can still add any product.
INSERT INTO cart_header (user_id, merchant_id)
VALUES (@demo_user_id, @demo_merchant_id)
ON DUPLICATE KEY UPDATE merchant_id = @demo_merchant_id;

-- ---------------------------------------------------------------------------
-- Minimal catalog.
-- category.status = 0 (normal), is_default: 0 default / 1 merchant category
-- product.status  = 0 (on sale), is_deleted = 0
-- stock_version advances when this seed resets stock, matching the merchant
-- "reset stock" baseline used by cancellation stock restoration.
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
    category_id, product_name, image_url, price, stock, stock_version,
    merchant_id, is_deleted, status, description, create_time, update_time
)
VALUES (
    @demo_hot_category_id, '演示宫保鸡丁',
    '/images/default-product.svg', 25.80, 100, 0,
    @demo_merchant_id, 0, 0, '微辣，用于验证下单到骑手抢单的完整链路',
    NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    category_id = @demo_hot_category_id,
    image_url = '/images/default-product.svg',
    price = 25.80,
    stock = 100,
    stock_version = stock_version + 1,
    is_deleted = 0,
    status = 0,
    description = '微辣，用于验证下单到骑手抢单的完整链路',
    update_time = NOW();
SET @demo_product_main_id = LAST_INSERT_ID();

INSERT INTO product (
    category_id, product_name, image_url, price, stock, stock_version,
    merchant_id, is_deleted, status, description, create_time, update_time
)
VALUES (
    @demo_default_category_id, '演示米饭',
    '/images/default-product.svg', 3.00, 200, 0,
    @demo_merchant_id, 0, 0, '演示主食',
    NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    category_id = @demo_default_category_id,
    image_url = '/images/default-product.svg',
    price = 3.00,
    stock = 200,
    stock_version = stock_version + 1,
    is_deleted = 0,
    status = 0,
    description = '演示主食',
    update_time = NOW();
SET @demo_product_rice_id = LAST_INSERT_ID();

INSERT INTO product (
    category_id, product_name, image_url, price, stock, stock_version,
    merchant_id, is_deleted, status, description, create_time, update_time
)
VALUES (
    @demo_hot_category_id, '演示可乐',
    '/images/default-product.svg', 5.00, 100, 0,
    @demo_merchant_id, 0, 0, '演示饮料',
    NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
    id = LAST_INSERT_ID(id),
    category_id = @demo_hot_category_id,
    image_url = '/images/default-product.svg',
    price = 5.00,
    stock = 100,
    stock_version = stock_version + 1,
    is_deleted = 0,
    status = 0,
    description = '演示饮料',
    update_time = NOW();
SET @demo_product_drink_id = LAST_INSERT_ID();

COMMIT;

-- ---------------------------------------------------------------------------
-- Verification output. The plaintext password is intentionally shown because
-- these are local-only demo accounts; do not use them outside local dev.
-- ---------------------------------------------------------------------------

SELECT
    '用户端' AS client,
    'username' AS login_field,
    username AS login_value,
    '12345678' AS login_password
FROM `user`
WHERE id = @demo_user_id
UNION ALL
SELECT '商家端', 'username', username, '12345678'
FROM merchant
WHERE id = @demo_merchant_id
UNION ALL
SELECT '骑手端', 'name', name, '12345678'
FROM rider
WHERE id = @demo_rider_id;

SELECT
    id AS merchant_id,
    merchant_name,
    address,
    longitude,
    latitude,
    status
FROM merchant
WHERE id = @demo_merchant_id;

SELECT
    id AS product_id,
    product_name,
    price,
    stock,
    status,
    is_deleted
FROM product
WHERE merchant_id = @demo_merchant_id
  AND product_name IN ('演示宫保鸡丁', '演示米饭', '演示可乐')
ORDER BY id;

-- Read-only check: legacy WAIT_ASSIGN tasks without pickup coordinates must
-- remain untouched. They are the MySQL-fallback comparison sample.
SELECT
    id AS legacy_task_id,
    order_id,
    status,
    pickup_longitude,
    pickup_latitude
FROM delivery_task
WHERE pickup_longitude IS NULL
   OR pickup_latitude IS NULL
ORDER BY id;
