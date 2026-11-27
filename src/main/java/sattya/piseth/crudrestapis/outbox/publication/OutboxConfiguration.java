package sattya.piseth.crudrestapis.outbox.publication;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {
    private static final String FALSE_VALUE = "false";

    @Bean static BeanFactoryPostProcessor validateOutboxEnablement(Environment environment) {
        return factory -> {
            var value = environment.getProperty("app.outbox.enabled", FALSE_VALUE);
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase(FALSE_VALUE))
                throw new IllegalArgumentException("app.outbox.enabled must be true or false");
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true")
    OutboxWorker outboxWorker(Environment environment, ObjectProvider<OutboxPublisher> adapters,
                              MeterRegistry metrics) {
        var settings = OutboxSettings.from(environment);
        var publishers = adapters.stream().toList();
        if (publishers.size() != 1) throw new IllegalStateException("Outbox requires exactly one explicit publication adapter");
        var publisher = publishers.getFirst();
        // Encapsulated resources deliberately are not DataSource/TransactionManager beans,
        // so Boot's API/JPA auto-configuration cannot back off or select the worker pool.
        var config = new HikariConfig();
        config.setPoolName("outbox-worker");
        config.setJdbcUrl(required(environment, "app.outbox.jdbc-url"));
        config.setUsername("crud_outbox");
        config.setPassword(required(environment, "app.outbox.password"));
        config.setMaximumPoolSize(settings.concurrency() + 1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("logServerErrorDetail", FALSE_VALUE);
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "10");
        config.setConnectionInitSql("SET statement_timeout='5s'");
        var pool = new HikariDataSource(config);
        try {
            verifyRole(new JdbcTemplate(pool));
            return new OutboxWorker(new OutboxStore(pool, settings), publisher, settings, metrics, pool::close);
        } catch (RuntimeException _) {
            pool.close();
            throw new IllegalStateException("Outbox database initialization failed; verify V5, restricted role, and connectivity");
        }
    }

    static void verifyRole(JdbcTemplate jdbc) {
        boolean safe = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT current_user='crud_outbox' AND NOT (rolsuper OR rolcreatedb OR rolcreaterole
                    OR rolreplication OR rolbypassrls OR rolinherit)
                    AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                    AND NOT has_schema_privilege(current_user,'app','CREATE')
                    AND has_table_privilege(current_user,'app.outbox_event','SELECT')
                    AND NOT has_database_privilege(current_user,current_database(),'CREATE')
                    AND NOT has_table_privilege(current_user,'app.outbox_event','INSERT,DELETE,TRUNCATE,UPDATE,REFERENCES,TRIGGER')
                    AND NOT has_any_column_privilege(current_user,'app.outbox_event','INSERT,REFERENCES')
                    AND NOT has_any_column_privilege(current_user,'app.notification','UPDATE')
                    AND NOT has_table_privilege(current_user,'app.flyway_schema_history','SELECT,INSERT,UPDATE,DELETE')
                    AND (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class
                         WHERE oid='app.outbox_event'::regclass)
                FROM pg_roles r WHERE rolname=current_user
                """, Boolean.class));
        if (!safe) throw new IllegalStateException("Worker database role is not restricted");
        for (var table : java.util.List.of("recipient", "notification", "notification_recipient", "delivery",
                "notification_preference", "ingestion_request", "processed_event", "flyway_schema_history")) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT has_table_privilege(current_user,?,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                        OR has_any_column_privilege(current_user,?,'SELECT,INSERT,UPDATE,REFERENCES')
                    """, Boolean.class, "app." + table, "app." + table)))
                throw new IllegalStateException("Worker can access unrelated application data");
        }
        for (var column : java.util.List.of("state", "attempt_count", "next_attempt_at", "claim_token", "lease_until",
                "failure_code", "published_at", "updated_at", "updated_by", "version")) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT has_column_privilege(current_user,'app.outbox_event',?,'UPDATE')", Boolean.class, column)))
                throw new IllegalStateException("Worker is missing required lifecycle grants");
        }
        for (var column : java.util.List.of("id", "tenant_id", "aggregate_id", "event_type", "schema_version",
                "payload", "created_at", "created_by")) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT has_column_privilege(current_user,'app.outbox_event',?,'UPDATE')", Boolean.class, column)))
                throw new IllegalStateException("Worker can mutate immutable event fields");
        }
    }
    private static String required(Environment environment, String name) {
        var value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
