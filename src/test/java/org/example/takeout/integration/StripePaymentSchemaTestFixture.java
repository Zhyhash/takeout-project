package org.example.takeout.integration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Legacy MySQL integration tests disable Flyway; refund guards still need the persisted payment reference table. */
final class StripePaymentSchemaTestFixture {
    private StripePaymentSchemaTestFixture() { }

    static void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V20261030_1__stripe_test_payments.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V20261108__stripe_order_refunds.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V20261109__stripe_refund_recovery.sql"));
            return null;
        });
    }
}
