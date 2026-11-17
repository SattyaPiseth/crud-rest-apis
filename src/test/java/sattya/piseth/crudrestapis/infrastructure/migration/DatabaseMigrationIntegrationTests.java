package sattya.piseth.crudrestapis.infrastructure.migration;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DatabaseMigrationIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-trixie");

    @Test
    void migratesAsSchemaOwnerAndCanBeRunAgainWithoutReapplyingMigrations() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            // Provision only this disposable test database, matching production's separate schema setup.
            statement.execute("CREATE ROLE crud_owner NOLOGIN");
            statement.execute("CREATE SCHEMA app AUTHORIZATION crud_owner");
            var environment = Map.of(
                    "MIGRATION_DB_URL", POSTGRES.getJdbcUrl(),
                    "MIGRATION_DB_USERNAME", POSTGRES.getUsername(),
                    "MIGRATION_DB_PASSWORD", POSTGRES.getPassword());

            DatabaseMigrator.migrate(environment);
            DatabaseMigrator.migrate(environment);

            try (var tables = statement.executeQuery(
                    "SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'app'")) {
                var owners = new HashMap<String, String>();
                while (tables.next()) {
                    owners.put(tables.getString(1), tables.getString(2));
                }
                assertThat(owners).containsOnlyKeys("recipient", "notification", "notification_recipient",
                        "delivery", "notification_preference", "outbox_event", "ingestion_request",
                        "processed_event", "flyway_schema_history");
                assertThat(owners.values()).containsOnly("crud_owner");
            }
            try (var history = statement.executeQuery(
                    "SELECT version, success FROM app.flyway_schema_history ORDER BY installed_rank")) {
                assertThat(history.next()).isTrue();
                assertThat(history.getString("version")).isEqualTo("1");
                assertThat(history.getBoolean("success")).isTrue();
                assertThat(history.next()).isTrue();
                assertThat(history.getString("version")).isEqualTo("2");
                assertThat(history.getBoolean("success")).isTrue();
                assertThat(history.next()).isFalse();
            }
        }
    }
}
