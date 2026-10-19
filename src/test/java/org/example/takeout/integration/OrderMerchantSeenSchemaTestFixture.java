package org.example.takeout.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** 为禁用 Flyway 且沿用已有本地 MySQL 库的测试补齐商家已读列。 */
final class OrderMerchantSeenSchemaTestFixture {

    private OrderMerchantSeenSchemaTestFixture() {
    }

    static void ensureMerchantSeenColumn(JdbcTemplate jdbcTemplate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'orders'
                  AND column_name = 'merchant_seen'
                """, Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE orders ADD COLUMN merchant_seen TINYINT(1) NULL DEFAULT NULL");
            // 与 V20261017 保持同一回填规则，并保留旧订单的更新时间。
            jdbcTemplate.update("""
                    UPDATE orders
                    SET merchant_seen = CASE WHEN status IN (0, 1, 2) THEN 0 ELSE 1 END,
                        update_time = update_time
                    WHERE merchant_seen IS NULL
                    """);
            jdbcTemplate.execute("ALTER TABLE orders MODIFY COLUMN merchant_seen TINYINT(1) NOT NULL DEFAULT 0");
        }
    }
}
