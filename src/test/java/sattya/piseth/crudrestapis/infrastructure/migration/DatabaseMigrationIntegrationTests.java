package sattya.piseth.crudrestapis.infrastructure.migration;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.SQLException;
import java.sql.Statement;
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
            statement.execute("CREATE ROLE crud_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE crud_migrator LOGIN PASSWORD 'migration-test-only' "
                    + "NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT crud_owner TO crud_migrator");
            var environment = Map.of(
                    "MIGRATION_DB_URL", POSTGRES.getJdbcUrl(),
                    "MIGRATION_DB_USERNAME", "crud_migrator",
                    "MIGRATION_DB_PASSWORD", "migration-test-only");

            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), "crud_migrator", "migration-test-only")
                    .schemas("app")
                    .defaultSchema("app")
                    .createSchemas(false)
                    .initSql("SET ROLE crud_owner")
                    .target(MigrationVersion.fromVersion("2"))
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var history = statement.executeQuery(
                    "SELECT count(*) FROM app.flyway_schema_history WHERE success AND version IN ('1', '2')")) {
                history.next();
                assertThat(history.getInt(1)).isEqualTo(2);
            }
            try (var rls = statement.executeQuery("""
                    SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='app' AND c.relname IN ('recipient','notification','notification_recipient',
                        'delivery','notification_preference','outbox_event','ingestion_request','processed_event')
                        AND c.relrowsecurity
                    """)) {
                rls.next();
                assertThat(rls.getInt(1)).isZero();
            }

            statement.execute("ALTER TABLE app.processed_event RENAME TO processed_event_unavailable");
            assertThatThrownBy(() -> DatabaseMigrator.migrate(environment)).isInstanceOf(RuntimeException.class);
            statement.execute("ALTER TABLE app.processed_event_unavailable RENAME TO processed_event");
            try (var rls = statement.executeQuery("""
                    SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='app' AND c.relname IN ('recipient','notification','notification_recipient',
                        'delivery','notification_preference','outbox_event','ingestion_request','processed_event')
                        AND c.relrowsecurity
                    """)) {
                rls.next();
                assertThat(rls.getInt(1)).isZero();
            }
            try (var version = statement.executeQuery(
                    "SELECT count(*) FROM app.flyway_schema_history WHERE version = '3'")) {
                version.next();
                assertThat(version.getInt(1)).isZero();
            }
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
            assertSuccessfulMigrationHistory(statement);
        }

        try (var migrationConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "crud_migrator", "migration-test-only");
             var statement = migrationConnection.createStatement()) {
            try (var role = statement.executeQuery("""
                    SELECT r.rolsuper OR r.rolinherit OR r.rolcreatedb OR r.rolcreaterole OR r.rolbypassrls,
                           pg_has_role(r.oid, 'crud_owner', 'MEMBER'),
                           has_schema_privilege(r.oid, 'app', 'CREATE')
                    FROM pg_roles r WHERE r.rolname = current_user
                    """)) {
                role.next();
                assertThat(role.getBoolean(1)).isFalse();
                assertThat(role.getBoolean(2)).isTrue();
                assertThat(role.getBoolean(3)).isFalse();
            }
            assertThatThrownBy(() -> statement.execute("CREATE TABLE app.migration_role_cannot_ddl (id integer)"))
                    .isInstanceOf(SQLException.class)
                    .extracting(exception -> ((SQLException) exception).getSQLState()).isEqualTo("42501");
        }
    }
    private static void assertSuccessfulMigrationHistory(Statement statement) throws SQLException {
        try (var history = statement.executeQuery(
                "SELECT version, success FROM app.flyway_schema_history ORDER BY installed_rank")) {
            assertThat(history.next()).isTrue();
            assertThat(history.getString("version")).isEqualTo("1");
            assertThat(history.getBoolean("success")).isTrue();
            assertThat(history.next()).isTrue();
            assertThat(history.getString("version")).isEqualTo("2");
            assertThat(history.getBoolean("success")).isTrue();
            assertThat(history.next()).isTrue();
            assertThat(history.getString("version")).isEqualTo("3");
            assertThat(history.getBoolean("success")).isTrue();
            assertThat(history.next()).isTrue();
            assertThat(history.getString("version")).isEqualTo("4");
            assertThat(history.getBoolean("success")).isTrue();
            assertThat(history.next()).isTrue();
            assertThat(history.getString("version")).isEqualTo("5");
            assertThat(history.getBoolean("success")).isTrue();
            assertThat(history.next()).isFalse();
        }
    }
}
