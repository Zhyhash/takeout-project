package org.example.takeout.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** 为禁用 Flyway 且沿用已有本地 MySQL 库的测试补齐订单取消来源列。 */
final class OrderCancelSourceSchemaTestFixture {

    private OrderCancelSourceSchemaTestFixture() {
    }

    static void ensureCancelSourceColumn(JdbcTemplate jdbcTemplate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'orders'
                  AND column_name = 'cancel_source'
                """, Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE orders ADD COLUMN cancel_source TINYINT NULL DEFAULT NULL");
        }
    }
}
