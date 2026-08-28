-- Tokeout MySQL 8 deployment baseline.
--
-- Source: MysqlApiIntegrationTest.recreateSchema().
-- Purpose: initialize a new deployment database without deleting existing data.
-- Note: CREATE TABLE IF NOT EXISTS does not migrate an existing table. Use a
-- versioned migration when an already deployed schema needs to be changed.

CREATE DATABASE IF NOT EXISTS `takeout`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `takeout`;

CREATE TABLE IF NOT EXISTS `user` (
    `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
    `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
    `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    `status` tinyint NOT NULL DEFAULT 1,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_user_phone` (`phone` ASC) USING BTREE,
    UNIQUE INDEX `uk_user_username` (`username` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `merchant` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `username` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
    `status` tinyint NOT NULL DEFAULT 0,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `merchant_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    `merchant_description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    `opening_time` time NOT NULL DEFAULT '08:00:00',
    `closing_time` time NOT NULL DEFAULT '22:00:00',
    `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_merchant_phone` (`phone` ASC) USING BTREE,
    UNIQUE INDEX `uk_merchant_username` (`username` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `category` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `merchant_id` bigint NOT NULL COMMENT '所属商家ID',
    `category_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分类名称',
    `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态: 0-正常使用, 1-非法分类 (对应 CategoryStatusEnum)',
    `is_default` tinyint NOT NULL DEFAULT 0 COMMENT '类型: 0-默认分类, 1-商家自主分类 (对应 CategoryDefaultEnum)',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_merchant_category` (`merchant_id` ASC, `category_name` ASC) USING BTREE,
    INDEX `idx_merchant_id` (`merchant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '商品分类表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `product` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `category_id` bigint NOT NULL COMMENT '分类ID',
    `product_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商品名称',
    `image_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '商品图片URL',
    `price` decimal(10, 2) NOT NULL COMMENT '商品价格',
    `stock` int NOT NULL COMMENT '库存数量',
    `merchant_id` bigint NOT NULL COMMENT '所属商家ID',
    `is_deleted` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除: 0-未删除, 1-已删除',
    `status` tinyint NOT NULL DEFAULT 1 COMMENT '商品状态: 0-在售, 1-下架, 2-售罄',
    `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '商品描述',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_category_id` (`category_id` ASC) USING BTREE,
    INDEX `idx_merchant_id` (`merchant_id` ASC) USING BTREE,
    UNIQUE INDEX `uk_merchant_product` (`merchant_id`, `product_name`) USING BTREE,
    CONSTRAINT `fk_product_category`
        FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '商品表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `cart` (
    `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '购物车id',
    `user_id` bigint UNSIGNED NOT NULL COMMENT '用户id',
    `product_id` bigint UNSIGNED NOT NULL COMMENT '商品id',
    `product_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `quantity` int NOT NULL DEFAULT 1 COMMENT '商品数量',
    `price` decimal(10, 2) NOT NULL COMMENT '加入购物车时的商品价格',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `merchant_id` bigint NOT NULL,
    `product_image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `version` int NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_user_product` (`user_id` ASC, `product_id` ASC) USING BTREE
        COMMENT '一个用户对同一个商品只能有一条购物车记录',
    INDEX `idx_user_id` (`user_id` ASC) USING BTREE,
    INDEX `idx_product_id` (`product_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '购物车表' ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `orders` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `user_id` bigint NOT NULL,
    `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '客户端下单请求唯一标识',
    `merchant_id` bigint NOT NULL,
    `merchant_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `total_amount` decimal(10, 2) NOT NULL,
    `status` tinyint NOT NULL DEFAULT 0,
    `receiver_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `receiver_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `finish_time` datetime NULL DEFAULT NULL,
    `original_amount` decimal(10, 2) NOT NULL DEFAULT 0.00,
    `discount_amount` decimal(10, 2) NOT NULL DEFAULT 0.00,
    `pay_time` datetime NULL DEFAULT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_order_no` (`order_no` ASC) USING BTREE,
    UNIQUE INDEX `uk_orders_user_request_id` (`user_id` ASC, `request_id` ASC) USING BTREE,
    INDEX `idx_order_user` (`user_id` ASC) USING BTREE,
    INDEX `idx_order_merchant` (`merchant_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `order_item` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `order_id` bigint NOT NULL,
    `product_id` bigint NOT NULL,
    `product_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `product_price` decimal(10, 2) NOT NULL,
    `quantity` int NOT NULL DEFAULT 1,
    `subtotal` decimal(10, 2) NOT NULL,
    `product_picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_order_item_order` (`order_id` ASC) USING BTREE,
    INDEX `idx_order_item_product` (`product_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `rider` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `phone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `status` int NOT NULL,
    `create_time` datetime NOT NULL,
    `update_time` datetime NULL DEFAULT NULL,
    `is_delete` int NOT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_rider_name` (`name` ASC) USING BTREE,
    UNIQUE INDEX `uk_rider_phone` (`phone` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

CREATE TABLE IF NOT EXISTS `delivery_task` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `order_id` bigint NOT NULL,
    `rider_id` bigint NULL DEFAULT NULL,
    `merchant_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `delivery_reward` decimal(10,2) NOT NULL COMMENT '配送奖励金额快照',
    `receiver_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人姓名快照',
    `status` int NOT NULL,
    `create_time` datetime NOT NULL COMMENT '商家制作完成，任务创建时间',
    `accepted_time` datetime NULL DEFAULT NULL COMMENT '骑手接取时间，当前也代表开始配送时间',
    `delivered_time` datetime NULL DEFAULT NULL COMMENT '骑手确认送达时间',
    `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
    `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人联系电话快照',
    `receiver_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货地址快照',
    `merchant_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商家地址快照',
    `merchant_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商家联系电话快照',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_delivery_task_order_id` (`order_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
