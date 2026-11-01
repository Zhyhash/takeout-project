package org.example.takeout.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

/** Fills the delivery-fee column for persistent MySQL tests that run without Flyway. */
final class OrderDeliveryFeeSchemaTestFixture {

    private OrderDeliveryFeeSchemaTestFixture() {
    }

    static void ensureDeliveryFeeColumn(JdbcTemplate jdbcTemplate) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'orders'
                  AND column_name = 'delivery_fee'
                """, Integer.class);

        if (count != null && count == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE orders
                    ADD COLUMN delivery_fee DECIMAL(10, 2) NULL DEFAULT NULL COMMENT '配送费' AFTER total_amount
                    """);
        }

        // Preserve historical update_time values just as V20261029 does.
        jdbcTemplate.update("""
                UPDATE orders
                SET delivery_fee = 0.00,
                    update_time = update_time
                WHERE delivery_fee IS NULL
                """);

        Map<String, Object> definition = jdbcTemplate.queryForMap("""
                SELECT is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'orders'
                  AND column_name = 'delivery_fee'
                """);
        String nullable = (String) definition.get("is_nullable");
        Object columnDefault = definition.get("column_default");
        boolean zeroDefault = columnDefault != null
                && new BigDecimal(columnDefault.toString()).compareTo(BigDecimal.ZERO) == 0;
        if (!"NO".equalsIgnoreCase(nullable) || !zeroDefault) {
            jdbcTemplate.execute("""
                    ALTER TABLE orders
                    MODIFY COLUMN delivery_fee DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '配送费'
                    """);
        }
    }
}
