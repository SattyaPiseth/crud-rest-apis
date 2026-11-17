package sattya.piseth.crudrestapis.infrastructure.migration;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import sattya.piseth.crudrestapis.notification.application.*;
import sattya.piseth.crudrestapis.inbox.application.InboxService;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import sattya.piseth.crudrestapis.shared.audit.BackgroundActor;
import sattya.piseth.crudrestapis.shared.persistence.TenantDatabaseContext;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.datasource.hikari.maximum-pool-size=2"})
@Testcontainers
class RestrictedDatabaseRoleTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-trixie");
    // Disposable test credentials only; never used by Compose or deployed environments.
    private static final String PASSWORD = "disposable-runtime-test";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws SQLException {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE crud_owner NOLOGIN");
            statement.execute("CREATE SCHEMA app AUTHORIZATION crud_owner");
            statement.execute("CREATE ROLE crud_app LOGIN PASSWORD '" + PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE ON SCHEMA app TO crud_app");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app "
                    + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO crud_app");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app "
                    + "GRANT USAGE, SELECT ON SEQUENCES TO crud_app");
        }
        DatabaseMigrator.migrate(Map.of("MIGRATION_DB_URL", POSTGRES.getJdbcUrl(),
                "MIGRATION_DB_USERNAME", POSTGRES.getUsername(), "MIGRATION_DB_PASSWORD", POSTGRES.getPassword()));
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "crud_app");
        properties.add("spring.datasource.password", () -> PASSWORD);
    }

    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired BackgroundActor actor;
    @Autowired TenantDatabaseContext tenantContext;
    @Autowired NotificationIngestionService ingestion;
    @Autowired NotificationEventConsumer consumer;
    @Autowired InboxService inbox;
    @Autowired JdbcTemplate jdbc;

    @Test
    void restrictedRuntimeRoleCanIngestAndReplayButCannotPerformDdl() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("crud_app");
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls "
                + "FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT has_table_privilege(current_user, 'app.flyway_schema_history', 'SELECT')
                    OR has_table_privilege(current_user, 'app.flyway_schema_history', 'INSERT')
                    OR has_table_privilege(current_user, 'app.flyway_schema_history', 'UPDATE')
                    OR has_table_privilege(current_user, 'app.flyway_schema_history', 'DELETE')
                """, Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM app.flyway_schema_history", Long.class))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo("42501");
        var source = new ProducerScope(UUID.randomUUID(), "restricted-test");
        var recipient = new Recipient(source.tenantId(), "issuer", "subject");
        actor.callAs("seed", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(source.tenantId());
            entityManager.persist(recipient);
            return null;
        }));
        var command = new IngestNotification("TYPE", "CATEGORY", "Title", "Body", null, List.of(recipient.getId()));
        var event = new NotificationRequestedEvent(UUID.randomUUID(), 1, "key", command);
        var accepted = consumer.consume(source, event);
        assertThat(accepted.replayed()).isFalse();
        var replay = new IngestionResult(accepted.notificationId(), true);
        assertThat(consumer.consume(source, event)).isEqualTo(replay);
        assertThat(actor.callAs("producer", () -> ingestion.ingest(source, "key", command))).isEqualTo(replay);
        var owner = new InboxService.Owner(source.tenantId(), "issuer", "subject");
        var page = inbox.list(owner, 0, 10);
        assertThat(page.items()).hasSize(1);
        assertThat(inbox.get(owner, page.items().getFirst().id()).title()).isEqualTo("Title");
        assertThat(actor.callAs("recipient", () ->
                inbox.update(owner, page.items().getFirst().id(), 0, false).version())).isEqualTo(1L);
        actor.callAs("verification", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(source.tenantId());
            for (var table : List.of("notification", "notification_recipient", "outbox_event", "ingestion_request", "processed_event")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM app." + table + " WHERE tenant_id = ?",
                        Long.class, source.tenantId())).isEqualTo(1L);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.notification WHERE id = ?",
                    Long.class, accepted.notificationId())).isEqualTo(1L);
            return null;
        }));
        for (var ddl : List.of("CREATE TABLE app.forbidden (id integer)",
                "ALTER TABLE app.notification ADD COLUMN forbidden integer", "SET ROLE crud_owner")) {
            assertThatThrownBy(() -> jdbc.execute(ddl)).hasRootCauseInstanceOf(SQLException.class)
                    .rootCause().extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    void rowSecurityHidesForeignTenantsAndRejectsCrossTenantWrites() {
        var tenantA = UUID.randomUUID();
        var tenantB = UUID.randomUUID();
        var recipientA = createRecipient(tenantA, "tenant-a");
        var recipientB = createRecipient(tenantB, "tenant-b");
        var policyTables = List.of("recipient", "notification", "notification_recipient", "delivery",
                "notification_preference", "outbox_event", "ingestion_request", "processed_event");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'app' AND c.relname IN
                    ('recipient','notification','notification_recipient','delivery','notification_preference',
                     'outbox_event','ingestion_request','processed_event')
                    AND c.relrowsecurity AND c.relforcerowsecurity
                """, Long.class)).isEqualTo(policyTables.size());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'app' AND tablename IN
                    ('recipient','notification','notification_recipient','delivery','notification_preference',
                     'outbox_event','ingestion_request','processed_event')
                    AND policyname = 'app_tenant_isolation' AND cmd = 'ALL'
                    AND permissive = 'PERMISSIVE' AND roles::text = '{public}'
                    AND qual LIKE '%app.tenant_id%' AND with_check LIKE '%app.tenant_id%'
                """, Long.class)).isEqualTo(policyTables.size());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                WHERE schemaname = 'app' AND tablename IN
                    ('recipient','notification','notification_recipient','delivery','notification_preference',
                     'outbox_event','ingestion_request','processed_event')
                """, Long.class)).isEqualTo(policyTables.size());
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT has_schema_privilege(current_user, 'app', 'CREATE')
                    OR has_table_privilege(current_user, 'app.recipient', 'TRUNCATE')
                    OR pg_has_role(current_user, 'crud_owner', 'MEMBER')
                """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_tables
                WHERE schemaname = 'app'
                    AND tablename IN ('recipient','notification','notification_recipient','delivery',
                        'notification_preference','outbox_event','ingestion_request','processed_event')
                    AND tableowner = 'crud_owner'
                """, Long.class)).isEqualTo(policyTables.size());

        actor.callAs("tenant-a", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(tenantA);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.recipient", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.recipient WHERE id=?", Long.class, recipientB)).isZero();
            assertThat(jdbc.update("UPDATE app.recipient SET active=false WHERE id=?", recipientB)).isZero();
            assertThat(jdbc.update("DELETE FROM app.recipient WHERE id=?", recipientB)).isZero();
            return null;
        }));

        assertThatThrownBy(() -> actor.callAs("tenant-a", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(tenantA);
            entityManager.persist(new Recipient(tenantB, "issuer", "cross-tenant-insert"));
            entityManager.flush();
            return null;
        }))).hasRootCauseInstanceOf(SQLException.class)
                .rootCause().extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo("42501");

        assertThatThrownBy(() -> actor.callAs("tenant-a", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(tenantA);
            return jdbc.update("UPDATE app.recipient SET tenant_id=? WHERE id=?", tenantB, recipientA);
        }))).hasRootCauseInstanceOf(SQLException.class)
                .rootCause().extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo("42501");

        assertThatThrownBy(() -> tenantContext.setTenant(tenantA))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("active transaction");

        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.recipient", Long.class)).isZero();
            return null;
        });
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', 'not-a-uuid', true)", String.class);
            return jdbc.queryForObject("SELECT count(*) FROM app.recipient", Long.class);
        })).hasRootCauseInstanceOf(SQLException.class)
                .rootCause().extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo("22P02");

        var pidA = tenantCountAndBackend(tenantA, 1L);
        var pidB = tenantCountAndBackend(tenantB, 1L);
        assertThat(pidB).isEqualTo(pidA);
        assertThat(unscopedCountAndBackend(pidB)).isZero();

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> tenantCount(tenantA));
            var b = executor.submit(() -> tenantCount(tenantB));
            assertThat(a.get()).isEqualTo(1L);
            assertThat(b.get()).isEqualTo(1L);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='app.notification_recipient'::regclass "
                + "AND contype='f' AND conname LIKE '%recipient%'", Long.class)).isPositive();
    }

    private UUID createRecipient(UUID tenant, String subject) {
        return actor.callAs("seed", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(tenant);
            var recipient = new Recipient(tenant, "issuer", subject);
            entityManager.persist(recipient);
            entityManager.flush();
            return recipient.getId();
        }));
    }

    private int tenantCountAndBackend(UUID tenant, long expected) {
        return actor.callAs("tenant-query", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(tenant);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.recipient", Long.class)).isEqualTo(expected);
            return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        }));
    }

    private long tenantCount(UUID tenant) {
        return actor.callAs("tenant-query", () -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantContext.setTenant(tenant);
            return jdbc.queryForObject("SELECT count(*) FROM app.recipient", Long.class);
        }));
    }

    private long unscopedCountAndBackend(int expectedBackendPid) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(expectedBackendPid);
            return jdbc.queryForObject("SELECT count(*) FROM app.recipient", Long.class);
        });
    }
}
