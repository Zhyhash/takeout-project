package org.example.takeout.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Bootstrap only the disposable MySQL instance created by reliability-lab/run.ps1. */
@EnabledIfSystemProperty(named = "takeout.lab.enabled", matches = "true")
class PhaseOneSchemaTest {
    @Test
    void migrateEmptyLabDatabaseAndCheckBusinessTablesUseInnoDB() throws Exception {
        int port = Integer.parseInt(System.getProperty("takeout.test.mysql.port"));
        String url = "jdbc:mysql://127.0.0.1:" + port + "/takeout_integration_test"
                + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
        Flyway flyway = Flyway.configure().dataSource(url, "root", "root").load();
        assertThat(flyway.info().applied()).isEmpty();
        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20261119");
        flyway.validate();

        try (var connection = DriverManager.getConnection(url, "root", "root");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT table_name, engine FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                    """)) {
                int count = 0;
                while (rows.next()) {
                    assertThat(rows.getString("engine")).as(rows.getString("table_name")).isEqualTo("InnoDB");
                    count++;
                }
                assertThat(count).isGreaterThan(1);
            }
            try (var row = statement.executeQuery("SELECT VERSION(), @@transaction_isolation")) {
                assertThat(row.next()).isTrue();
                System.out.printf("LAB MySQL=%s isolation=%s schema=%s%n", row.getString(1), row.getString(2),
                        flyway.info().current().getVersion());
            }
        }
    }
}
