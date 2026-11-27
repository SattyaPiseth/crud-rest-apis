package sattya.piseth.crudrestapis.outbox.publication;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

/** Test-only process harness for a dedicated localhost PostgreSQL database. */
public final class PersistentOutboxOpsProcess {
    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/outbox_ops";
    private static final UUID TENANT = UUID.fromString("de4e4b7f-8b5a-4f4c-9f20-7bc929d99b02");
    private static final UUID PROBE_TENANT = UUID.fromString("7ca02cc2-1725-47fd-a4a6-42f3db985237");
    private static final UUID EVENT = UUID.fromString("5ca62910-59ea-45e8-bbc0-9c2a94f3f42c");
    private static final Duration WAIT = Duration.ofSeconds(100);
    private static JdbcTemplate admin;

    private PersistentOutboxOpsProcess() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0)
            throw new IllegalArgumentException("This operational harness accepts configuration through Gradle properties only");
        var mode = System.getProperty("outbox.ops.mode", "ack");
        if (!java.util.List.of("probe", "hold", "retry-then-ack", "ack").contains(mode))
            throw new IllegalArgumentException("Unsupported operational harness mode");
        bootstrap(Boolean.parseBoolean(System.getProperty("outbox.ops.seed", "false")));
        System.out.printf("OPS_BOOTSTRAP database=outbox_ops tenant=%s event=%s mode=%s pid=%d%n",
                TENANT, EVENT, mode, ProcessHandle.current().pid());

        var context = new SpringApplicationBuilder(
                sattya.piseth.crudrestapis.CrudRestApisApplication.class, OpsConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .run(applicationArguments(mode));
        try (context) {
            var worker = context.getBean(OutboxWorker.class);
            if (!worker.isRunning()) throw new IllegalStateException("Outbox worker did not start");
            System.out.printf("OPS_WORKER_STARTED running=%s tenant=%s%n", worker.isRunning(), workerTenant(mode));
            if (mode.equals("probe")) return;
            if (mode.equals("hold")) {
                new CountDownLatch(1).await();
                return;
            }
            verifyRecoveryAndMetrics(context.getBean(MeterRegistry.class));
        }
    }

    private static String[] applicationArguments(String mode) {
        var tenant = workerTenant(mode);
        return new String[]{
                "--spring.main.web-application-type=servlet",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + JDBC_URL,
                "--spring.datasource.username=crud_app",
                "--spring.datasource.password=${OUTBOX_OPS_APP_PASSWORD}",
                "--app.api.enabled=false",
                "--app.outbox.enabled=true",
                "--app.outbox.jdbc-url=" + JDBC_URL,
                "--app.outbox.password=${OUTBOX_OPS_WORKER_PASSWORD}",
                "--app.outbox.tenants=" + tenant,
                "--app.outbox.concurrency=1",
                "--app.outbox.batch-size=1",
                "--app.outbox.max-attempts=5",
                "--app.outbox.poll-interval=20ms",
                "--app.outbox.publication-timeout=60s",
                "--app.outbox.lease-duration=70s",
                "--app.outbox.finalization-margin=5s",
                "--app.outbox.initial-retry=100ms",
                "--app.outbox.max-retry=250ms",
                "--app.outbox.shutdown-timeout=1s"
        };
    }

    private static UUID workerTenant(String mode) {
        return mode.equals("probe") ? PROBE_TENANT : TENANT;
    }

    private static void bootstrap(boolean seed) throws Exception {
        var adminPassword = requiredEnvironment("OUTBOX_OPS_ADMIN_PASSWORD");
        var appPassword = requiredEnvironment("OUTBOX_OPS_APP_PASSWORD");
        var workerPassword = requiredEnvironment("OUTBOX_OPS_WORKER_PASSWORD");
        var dataSource = new DriverManagerDataSource(JDBC_URL, "postgres", adminPassword);
        admin = new JdbcTemplate(dataSource);
        if (!"outbox_ops".equals(admin.queryForObject("SELECT current_database()", String.class)))
            throw new IllegalStateException("Operational harness refused an unexpected database");

        createRole("crud_owner", "CREATE ROLE crud_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        createRole("crud_app", "CREATE ROLE crud_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        createRole("crud_outbox", "CREATE ROLE crud_outbox LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        admin.execute("ALTER ROLE crud_app PASSWORD " + sqlLiteral(appPassword));
        admin.execute("ALTER ROLE crud_outbox PASSWORD " + sqlLiteral(workerPassword));
        if (Boolean.FALSE.equals(admin.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='app')", Boolean.class)))
            admin.execute("CREATE SCHEMA app AUTHORIZATION crud_owner");
        admin.execute("GRANT CONNECT ON DATABASE outbox_ops TO crud_app, crud_outbox");
        admin.execute("GRANT USAGE ON SCHEMA app TO crud_app, crud_outbox");
        admin.execute("ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO crud_app");

        Flyway.configure().dataSource(JDBC_URL, "postgres", adminPassword)
                .schemas("app").defaultSchema("app").createSchemas(false)
                .initSql("SET ROLE crud_owner").load().migrate();

        admin.execute("GRANT SELECT ON app.outbox_event TO crud_outbox");
        admin.execute("GRANT UPDATE (state,attempt_count,next_attempt_at,claim_token,lease_until,failure_code,published_at,updated_at,updated_by,version) ON app.outbox_event TO crud_outbox");
        admin.execute("ALTER ROLE crud_outbox SET statement_timeout='5s'");
        admin.execute("ALTER ROLE crud_outbox SET lock_timeout='1s'");
        admin.execute("ALTER ROLE crud_outbox SET idle_in_transaction_session_timeout='10s'");

        boolean forcedRls = Boolean.TRUE.equals(admin.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='app.outbox_event'::regclass", Boolean.class));
        if (!forcedRls) throw new IllegalStateException("Forced outbox RLS is not enabled");
        if (seed) {
            admin.update("""
                    INSERT INTO app.outbox_event (id,tenant_id,version,created_at,updated_at,created_by,updated_by,
                        aggregate_id,event_type,schema_version,payload)
                    VALUES (?,?,0,clock_timestamp(),clock_timestamp(),'producer:ops-test','producer:ops-test',?,
                        'notification.created',1,'{"recipientCount":1}')
                    ON CONFLICT (id) DO NOTHING
                    """, EVENT, TENANT, UUID.randomUUID());
        }
        var row = admin.queryForMap("SELECT state,attempt_count FROM app.outbox_event WHERE id=? AND tenant_id=?", EVENT, TENANT);
        System.out.printf("OPS_DATABASE_STATE state=%s attempts=%s forced_rls=%s%n",
                row.get("state"), row.get("attempt_count"), forcedRls);
    }

    private static void createRole(String role, String sql) {
        boolean exists = Boolean.TRUE.equals(admin.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=?)", Boolean.class, role));
        if (!exists) admin.execute(sql);
    }

    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void verifyRecoveryAndMetrics(MeterRegistry metrics) throws Exception {
        var previous = new AtomicReference<String>();
        await().alias("outbox event is published")
                .pollInterval(Duration.ofMillis(20))
                .atMost(WAIT)
                .until(() -> {
                    var currentRow = admin.queryForMap(
                            "SELECT state,attempt_count,published_at FROM app.outbox_event WHERE id=?", EVENT);
                    var transition = currentRow.get("state") + ":" + currentRow.get("attempt_count");
                    if (!transition.equals(previous.getAndSet(transition))) {
                        System.out.printf("OPS_STATE state=%s attempts=%s published_at=%s%n",
                                currentRow.get("state"), currentRow.get("attempt_count"), currentRow.get("published_at"));
                    }
                    return "PUBLISHED".equals(currentRow.get("state"));
                });
        Map<String, Object> row = admin.queryForMap(
                "SELECT state,attempt_count,published_at FROM app.outbox_event WHERE id=?", EVENT);
        int expectedAttempts = modeIsRetryThenAck() ? 3 : 1;
        if (!"PUBLISHED".equals(row.get("state")) || !Integer.valueOf(expectedAttempts).equals(row.get("attempt_count")))
            throw new IllegalStateException("Event did not recover, retry, and publish within the verification window");

        var unknown = metrics.counter("outbox.attempts", "outcome", "UNKNOWN").count();
        var acknowledged = metrics.counter("outbox.attempts", "outcome", "ACKNOWLEDGED").count();
        var applied = metrics.counter("outbox.finalizations", "result", "APPLIED").count();
        var processing = metrics.timer("outbox.processing").count();
        var publishedGauge = metrics.find("outbox.events").tag("state", "PUBLISHED").gauge();
        var published = publishedGauge == null ? Double.NaN : publishedGauge.value();
        double expectedUnknown = modeIsRetryThenAck() ? 1 : 0;
        double expectedCount = modeIsRetryThenAck() ? 2 : 1;
        if (unknown != expectedUnknown || acknowledged != 1 || applied != expectedCount
                || processing != expectedCount || published != 1.0)
            throw new IllegalStateException("Operational metrics did not match expected recovered/retry outcomes");
        verifyTenantRls();
        System.out.printf("OPS_METRICS unknown=%s acknowledged=%s finalizations_applied=%s processing_count=%s published_gauge=%.0f%n",
                unknown, acknowledged, applied, processing, published);
        System.out.printf("OPS_RESULT event=%s tenant=%s state=%s attempts=%s simulated_ack=true kafka_delivery=false%n",
                EVENT, TENANT, row.get("state"), row.get("attempt_count"));
    }

    private static boolean modeIsRetryThenAck() {
        return "retry-then-ack".equals(System.getProperty("outbox.ops.mode", "ack"));
    }

    private static void verifyTenantRls() throws Exception {
        try (var connection = DriverManager.getConnection(
                JDBC_URL, "crud_outbox", requiredEnvironment("OUTBOX_OPS_WORKER_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                try (var setTenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    setTenant.setString(1, TENANT.toString());
                    setTenant.execute();
                }
                try (var query = connection.prepareStatement("SELECT count(*) FROM app.outbox_event WHERE id=?")) {
                    query.setObject(1, EVENT);
                    try (var result = query.executeQuery()) {
                        result.next();
                        if (result.getInt(1) != 1) throw new IllegalStateException("Worker could not see its authorized tenant event");
                    }
                }
                try (var setTenant = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    setTenant.setString(1, UUID.randomUUID().toString());
                    setTenant.execute();
                }
                try (var query = connection.prepareStatement("SELECT count(*) FROM app.outbox_event WHERE id=?")) {
                    query.setObject(1, EVENT);
                    try (var result = query.executeQuery()) {
                        result.next();
                        if (result.getInt(1) != 0) throw new IllegalStateException("Worker crossed the tenant RLS boundary");
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        System.out.println("OPS_TENANT_ISOLATION authorized_visible=true other_tenant_visible=false role=crud_outbox");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OpsConfiguration {
        @Bean @Primary SimpleMeterRegistry opsMeterRegistry() { return new SimpleMeterRegistry(); }

        @Bean OutboxPublisher opsPublisher() {
            var mode = System.getProperty("outbox.ops.mode", "ack");
            var calls = new AtomicInteger();
            return (publication, timeout) -> {
                int call = calls.incrementAndGet();
                System.out.printf("OPS_PUBLISHER mode=%s call=%d event=%s timeout_ms=%d%n",
                        mode, call, publication.eventId(), timeout.toMillis());
                if (mode.equals("hold")) {
                    new CountDownLatch(1).await();
                    return PublicationOutcome.UNKNOWN;
                }
                if (mode.equals("retry-then-ack") && call == 1) return PublicationOutcome.UNKNOWN;
                return PublicationOutcome.ACKNOWLEDGED;
            };
        }
    }
}