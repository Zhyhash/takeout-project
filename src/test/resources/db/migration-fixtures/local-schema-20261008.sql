-- Business DDL captured from the local MySQL schema at Flyway version 20261008.
-- History, scratch tables and instance-specific AUTO_INCREMENT counters are omitted.

CREATE TABLE `cache_invalidation_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cache_key` varchar(255) NOT NULL,
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已经失败的次数',
  `next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '任务状态: 0-PENDING, 1-DONE',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_created_time` (`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='缓存失效任务表';

CREATE TABLE `cart` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '购物车id',
  `user_id` bigint unsigned NOT NULL COMMENT '用户id',
  `product_id` bigint unsigned NOT NULL COMMENT '商品id',
  `product_name` varchar(255) NOT NULL,
  `quantity` int NOT NULL DEFAULT '1' COMMENT '商品数量',
  `price` decimal(10,2) NOT NULL COMMENT '加入购物车时的商品价格',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `merchant_id` bigint NOT NULL,
  `product_image` varchar(255) NOT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product` (`user_id`,`product_id`) COMMENT '一个用户对同一个商品只能有一条购物车记录',
  KEY `idx_user_id` (`user_id`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车表';

CREATE TABLE `cart_header` (
  `user_id` bigint unsigned NOT NULL,
  `merchant_id` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `merchant_id` bigint NOT NULL COMMENT '所属商家ID',
  `category_name` varchar(255) NOT NULL COMMENT '分类名称',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态: 0-正常使用, 1-非法分类 (对应 CategoryStatusEnum)',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '类型: 0-默认分类, 1-商家自主分类 (对应 CategoryDefaultEnum)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_category` (`merchant_id`,`category_name`) USING BTREE,
  KEY `idx_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品分类表';

CREATE TABLE `delivery_task` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `rider_id` bigint DEFAULT NULL,
  `merchant_name` varchar(255) NOT NULL,
  `status` int NOT NULL,
  `create_time` datetime NOT NULL COMMENT '商家制作完成、任务创建时间',
  `accepted_time` datetime DEFAULT NULL COMMENT '骑手接取时间，当前也代表开始配送时间',
  `delivered_time` datetime DEFAULT NULL COMMENT '骑手确认送达时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `receiver_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `receiver_phone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `merchant_address` varchar(255) NOT NULL,
  `merchant_phone` varchar(255) NOT NULL,
  `receiver_name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `merchant` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
  `status` tinyint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `merchant_name` varchar(255) NOT NULL,
  `picture` varchar(255) DEFAULT NULL,
  `merchant_description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `opening_time` time NOT NULL DEFAULT '08:00:00',
  `closing_time` time NOT NULL DEFAULT '22:00:00',
  `version` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_phone` (`phone`) USING BTREE,
  UNIQUE KEY `uk_merchant_username` (`username`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `product_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `product_price` decimal(10,2) NOT NULL,
  `quantity` int NOT NULL DEFAULT '1',
  `subtotal` decimal(10,2) NOT NULL,
  `product_picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order_item_order` (`order_id`) USING BTREE,
  KEY `idx_order_item_product` (`product_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `merchant_id` bigint NOT NULL,
  `merchant_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `total_amount` decimal(10,2) NOT NULL,
  `status` tinyint NOT NULL DEFAULT '0',
  `receiver_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `receiver_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `finish_time` datetime DEFAULT NULL,
  `original_amount` decimal(10,2) NOT NULL DEFAULT '0.00',
  `discount_amount` decimal(10,2) NOT NULL DEFAULT '0.00',
  `pay_time` datetime DEFAULT NULL,
  `request_Id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '收货信息和备注的SHA-256请求指纹',
  `timeout_cancel_available_at` datetime NOT NULL DEFAULT ((now() + interval 30 minute)) COMMENT '下次允许超时取消扫描的时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_orders_user_request_id` (`user_id`,`request_Id`) USING BTREE,
  KEY `idx_order_user` (`user_id`) USING BTREE,
  KEY `idx_order_merchant` (`merchant_id`) USING BTREE,
  KEY `idx_order_status_timeout_cancel_time_id` (`status`,`timeout_cancel_available_at`,`id`),
  KEY `idx_order_status_create_time_id` (`status`,`create_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category_id` bigint DEFAULT NULL,
  `product_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `image_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `price` decimal(10,2) NOT NULL,
  `stock` int NOT NULL DEFAULT '0',
  `merchant_id` bigint NOT NULL,
  `is_deleted` tinyint NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '商品状态: 0-在售, 1-下架, 2-售罄',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `version` int NOT NULL DEFAULT '0',
  `active_name_guard` tinyint GENERATED ALWAYS AS ((case when (`is_deleted` = 0) then 1 else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_product_active` (`merchant_id`,`product_name`,`active_name_guard`),
  KEY `idx_product_merchant` (`merchant_id`) USING BTREE,
  KEY `idx_product_category` (`category_id`) USING BTREE,
  CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `rider` (
  `id` bigint NOT NULL,
  `name` varchar(255) NOT NULL,
  `phone` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `status` int NOT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime DEFAULT NULL,
  `is_delete` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`) USING BTREE,
  UNIQUE KEY `uk_phone` (`phone`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone` (`phone`) USING BTREE,
  UNIQUE KEY `uk_user_username` (`username`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

