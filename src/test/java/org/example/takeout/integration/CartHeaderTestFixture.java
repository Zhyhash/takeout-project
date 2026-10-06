package org.example.takeout.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** Test seeds bypass registration and must explicitly maintain their cart header. */
final class CartHeaderTestFixture {

    private CartHeaderTestFixture() {
    }

    static void ensureTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cart_header (
                    user_id BIGINT UNSIGNED NOT NULL,
                    merchant_id BIGINT UNSIGNED DEFAULT NULL,
                    PRIMARY KEY (user_id)
                ) ENGINE = InnoDB CHARACTER SET = utf8mb4
                  COLLATE = utf8mb4_0900_ai_ci
                """);
    }

    static void insertEmpty(JdbcTemplate jdbcTemplate, Long userId) {
        jdbcTemplate.update("INSERT INTO cart_header (user_id, merchant_id) VALUES (?, NULL)", userId);
    }

    static void bind(JdbcTemplate jdbcTemplate, Long userId, Long merchantId) {
        jdbcTemplate.update("UPDATE cart_header SET merchant_id = ? WHERE user_id = ?", merchantId, userId);
    }

    static Long merchantId(JdbcTemplate jdbcTemplate, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT merchant_id FROM cart_header WHERE user_id = ?", Long.class, userId);
    }
}
