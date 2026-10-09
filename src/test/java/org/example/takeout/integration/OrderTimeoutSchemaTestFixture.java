package org.example.takeout.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** Applies the order timeout column to existing local MySQL integration databases. */
final class OrderTimeoutSchemaTestFixture {

    private OrderTimeoutSchemaTestFixture() {
    }

    static void ensureTimeoutCancellationColumn(JdbcTemplate jdbcTemplate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'orders'
                  AND column_name = 'timeout_cancel_available_at'
                """, Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE orders
                    ADD COLUMN timeout_cancel_available_at DATETIME NULL DEFAULT NULL
                    """);
            jdbcTemplate.update("""
                    UPDATE orders
                    SET timeout_cancel_available_at = DATE_ADD(create_time, INTERVAL 30 MINUTE)
                    WHERE timeout_cancel_available_at IS NULL
                    """);
            jdbcTemplate.execute("""
                    ALTER TABLE orders
                    MODIFY COLUMN timeout_cancel_available_at DATETIME NOT NULL
                        DEFAULT (CURRENT_TIMESTAMP + INTERVAL 30 MINUTE)
                    """);
        }
    }
}
