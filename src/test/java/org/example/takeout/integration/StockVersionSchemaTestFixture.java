package org.example.takeout.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/** Applies the independent stock baseline columns to existing local MySQL integration databases. */
final class StockVersionSchemaTestFixture {

    private StockVersionSchemaTestFixture() {
    }

    static void ensureStockVersionColumns(JdbcTemplate jdbcTemplate) {
        ensureColumn(jdbcTemplate, "product", "stock_version",
                "ALTER TABLE product ADD COLUMN stock_version INT NOT NULL DEFAULT 0 "
                        + "COMMENT '库存基准版本：商家显式重设库存时递增' AFTER stock");
        ensureColumn(jdbcTemplate, "order_item", "stock_version",
                "ALTER TABLE order_item ADD COLUMN stock_version INT NOT NULL DEFAULT 0 "
                        + "COMMENT '预扣库存时的商品库存基准版本' AFTER quantity");
    }

    private static void ensureColumn(JdbcTemplate jdbcTemplate,
                                     String tableName,
                                     String columnName,
                                     String alterSql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count != null && count == 0) {
            jdbcTemplate.execute(alterSql);
        }
    }
}
