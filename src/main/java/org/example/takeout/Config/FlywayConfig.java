package org.example.takeout.Config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 no longer supplies Flyway auto-configuration in the
 * spring-boot-autoconfigure module used by this project.  Build and run the
 * Flyway instance explicitly so migrations execute before the application is
 * ready to serve requests.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "spring.flyway",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public Flyway flyway(
            @Value("${spring.flyway.url:${DB_URL}}") String url,
            @Value("${spring.flyway.user:${DB_USERNAME}}") String user,
            @Value("${spring.flyway.password:${DB_PASSWORD}}") String password,
            @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
            @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate,
            @Value("${spring.flyway.baseline-version:1}") String baselineVersion,
            @Value("${spring.flyway.validate-on-migrate:true}") boolean validateOnMigrate,
            @Value("${spring.flyway.connect-retries:10}") int connectRetries,
            @Value("${spring.flyway.connect-retries-interval:5}") int connectRetriesInterval) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations.split("\\s*,\\s*"))
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion)
                .validateOnMigrate(validateOnMigrate)
                .connectRetries(connectRetries)
                .connectRetriesInterval(connectRetriesInterval)
                .load();
    }

    @Bean
    public FlywayMigrationRunner flywayMigrationRunner(Flyway flyway) {
        return new FlywayMigrationRunner(flyway);
    }

    private record FlywayMigrationRunner(Flyway flyway) implements InitializingBean {

        @Override
            public void afterPropertiesSet() {
                var result = flyway.migrate();
                var current = flyway.info().current();
                log.info("Flyway migration completed: {} migrations applied, schema version {}",
                        result.migrationsExecuted,
                        current == null ? "none" : current.getVersion());
            }
        }
}
