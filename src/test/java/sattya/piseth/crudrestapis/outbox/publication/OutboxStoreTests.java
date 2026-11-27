package sattya.piseth.crudrestapis.outbox.publication;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class OutboxStoreTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-trixie").withDatabaseName("crud_rest_apis");
    static JdbcTemplate admin;
    static HikariDataSource pool;
    static Map<String, Object> oldPending;
    static Map<String, Object> oldPublished;
    static Map<String, Object> upgradedPending;
    static Map<String, Object> upgradedPublished;
    UUID tenant;
    OutboxStore store;
    OutboxSettings settings;

    @BeforeAll static void initialize() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE ROLE crud_owner NOLOGIN");
        admin.execute("CREATE ROLE crud_app NOLOGIN");
        admin.execute("CREATE SCHEMA app AUTHORIZATION crud_owner");
        migrations("4").migrate();
        UUID first = seed(UUID.randomUUID());
        UUID second = seed(UUID.randomUUID());
        admin.update("UPDATE app.outbox_event SET published_at=clock_timestamp() WHERE id=?", second);
        oldPending = row(first); oldPublished = row(second);
        // Inject a late V5 failure to verify all earlier DDL/backfill/policy work rolls back.
        admin.execute("ALTER TABLE app.outbox_event ADD CONSTRAINT outbox_state CHECK (true)");
        assertThatThrownBy(() -> migrations(null).migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(admin.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='app' AND table_name='outbox_event' AND column_name='state'",Integer.class)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname='app' AND policyname='outbox_v5_backfill'",Integer.class)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.flyway_schema_history WHERE version='5'",Integer.class)).isZero();
        assertThat(row(first)).isEqualTo(oldPending); assertThat(row(second)).isEqualTo(oldPublished);
        admin.execute("ALTER TABLE app.outbox_event DROP CONSTRAINT outbox_state");
        migrations(null).migrate();
        upgradedPending = row(first); upgradedPublished = row(second);
        POSTGRES.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(
                Path.of("docker/postgres/provision-outbox-worker.sql")), "/tmp/provision-outbox-worker.sql");
        var provisioning=POSTGRES.execInContainer("env","OUTBOX_DB_PASSWORD=test-worker-only","psql",
                "-U",POSTGRES.getUsername(),"-d",POSTGRES.getDatabaseName(),"-f","/tmp/provision-outbox-worker.sql");
        assertThat(provisioning.getExitCode()).as("Repository worker provisioning script succeeds").isZero();
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl()); config.setUsername("crud_outbox"); config.setPassword("test-worker-only");
        config.setMaximumPoolSize(4); config.setMinimumIdle(0);
        pool = new HikariDataSource(config);
    }
    static Flyway migrations(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("app").defaultSchema("app").createSchemas(false).initSql("SET ROLE crud_owner");
        if (target != null) config.target(target);
        return config.load();
    }
    @AfterAll static void closePool() { if (pool != null) pool.close(); }
    @BeforeEach void prepare() {
        admin.execute("DELETE FROM app.outbox_event");
        tenant = UUID.randomUUID();
        settings = settings(List.of(tenant), 4, 4, 3);
        store = new OutboxStore(pool, settings);
    }
    static OutboxSettings settings(List<UUID> tenants, int concurrency, int batch, int attempts) {
        return new OutboxSettings(tenants, concurrency, batch, attempts, Duration.ofMillis(20),
                Duration.ofMillis(100), Duration.ofSeconds(6), Duration.ofSeconds(5),
                Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofMillis(200));
    }
    static UUID seed(UUID tenant) {
        var id = UUID.randomUUID();
        admin.update("""
                INSERT INTO app.outbox_event (id,tenant_id,version,created_at,updated_at,created_by,updated_by,
                    aggregate_id,event_type,schema_version,payload)
                VALUES (?,?,0,clock_timestamp(),clock_timestamp(),'producer:original','producer:original',?,'notification.created',1,'{"recipientCount":1}')
                """, id, tenant, UUID.randomUUID());
        return id;
    }
    static Map<String,Object> row(UUID id) { return admin.queryForMap("SELECT * FROM app.outbox_event WHERE id=?", id); }
    static void expire(UUID id) { admin.update("UPDATE app.outbox_event SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id); }
    static void due(UUID id) { admin.update("UPDATE app.outbox_event SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",id); }

    @Test void v4UpgradePreservesExistingRecordsAndForcedRls() {
        assertThat(upgradedPending).containsAllEntriesOf(oldPending).containsEntry("state","PENDING");
        assertThat(upgradedPublished).containsAllEntriesOf(oldPublished).containsEntry("state","PUBLISHED");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.flyway_schema_history WHERE success",Integer.class)).isEqualTo(5);
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname='app' AND policyname='outbox_v5_backfill'",Integer.class)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace='app'::regnamespace AND relrowsecurity AND relforcerowsecurity",Integer.class)).isEqualTo(8);
        migrations(null).validate();
    }

    @Test void concurrentWorkersReceiveDisjointClaims() throws Exception {
        for (int i=0;i<8;i++) seed(tenant);
        var second = new OutboxStore(pool,settings);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(); return store.claim(tenant,4); });
            var b = executor.submit(() -> { barrier.await(); return second.claim(tenant,4); });
            var claimsA=a.get(10,TimeUnit.SECONDS); var claimsB=b.get(10,TimeUnit.SECONDS);
            assertThat(claimsA).hasSize(4); assertThat(claimsB).hasSize(4);
            assertThat(claimsA.stream().map(c -> c.event().eventId())).doesNotContainAnyElementsOf(claimsB.stream().map(c -> c.event().eventId()).toList());
            assertThat(store.claim(tenant,4)).isEmpty();
        }
    }

    @Test void lockedRowsAreSkippedWithoutWaiting() throws Exception {
        var locked=seed(tenant); var available=seed(tenant);
        try (var connection=POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM app.outbox_event WHERE id=? FOR UPDATE")) {
                statement.setObject(1,locked); statement.executeQuery().close();
            }
            assertThat(store.claim(tenant,2)).extracting(c -> c.event().eventId()).containsExactly(available);
            connection.rollback();
        }
    }

    @ParameterizedTest @EnumSource(PublicationOutcome.class)
    void outcomesPreserveEnvelopeAndAudit(PublicationOutcome outcome) {
        var id=seed(tenant); var before=row(id);
        var claim=store.claim(tenant,1).getFirst();
        assertThat(row(id)).containsEntry("updated_by",OutboxStore.ACTOR).containsEntry("version",1L);
        assertThat(store.finish(claim,outcome,Duration.ofMillis(50))).isTrue();
        String expected=switch(outcome) { case ACKNOWLEDGED -> "PUBLISHED"; case PERMANENT -> "FAILED"; default -> "RETRY"; };
        var after=row(id);
        assertThat(after).containsEntry("state",expected).containsEntry("version",2L)
                .containsEntry("created_by",before.get("created_by")).containsEntry("created_at",before.get("created_at"))
                .containsEntry("updated_by",OutboxStore.ACTOR).containsEntry("payload",before.get("payload"))
                .containsEntry("aggregate_id",before.get("aggregate_id")).containsEntry("attempt_count",1);
        assertThat(after.get("claim_token")).isNull(); assertThat(after.get("lease_until")).isNull();
        assertThat(store.finish(claim,outcome,Duration.ofMillis(50))).isFalse();
        assertThat(row(id)).isEqualTo(after);
    }

    @Test void expiredAndSupersededWorkersCannotChangeAnyPersistedField() {
        var id=seed(tenant); var old=store.claim(tenant,1).getFirst(); expire(id);
        var expired=row(id);
        assertThat(store.finish(old,PublicationOutcome.ACKNOWLEDGED,Duration.ofMillis(10))).isFalse();
        assertThat(row(id)).isEqualTo(expired);
        var fresh=store.claim(tenant,1).getFirst();
        assertThat(fresh.token()).isNotEqualTo(old.token());
        assertThat(fresh.event()).isEqualTo(old.event());
        assertThat(fresh.attempt()).isEqualTo(2);
        var reclaimed=row(id);
        for(var outcome:PublicationOutcome.values()) assertThat(store.finish(old,outcome,Duration.ofMillis(10))).isFalse();
        assertThat(row(id)).isEqualTo(reclaimed);
        assertThat(store.finish(fresh,PublicationOutcome.ACKNOWLEDGED,Duration.ofMillis(10))).isTrue();
    }

    @Test void retryEligibilityAndCrashExhaustionArePersistent() {
        var id=seed(tenant); var claim=store.claim(tenant,1).getFirst();
        assertThat(store.finish(claim,PublicationOutcome.UNKNOWN,Duration.ofMillis(100))).isTrue();
        admin.update("UPDATE app.outbox_event SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id=?",id);
        assertThat(store.claim(tenant,1)).isEmpty(); due(id);
        var retry=store.claim(tenant,1).getFirst(); expire(id);
        var last=store.claim(tenant,1).getFirst(); expire(id);
        assertThat(retry.attempt()).isEqualTo(2); assertThat(last.attempt()).isEqualTo(3);
        assertThat(store.claim(tenant,1)).isEmpty();
        assertThat(row(id)).containsEntry("state","FAILED").containsEntry("failure_code","EXHAUSTED").containsEntry("attempt_count",3);
        assertThat(new OutboxStore(pool,settings).claim(tenant,1)).isEmpty();
    }

    @Test void finalFailureUsesPersistedAttemptCountNotSuppliedClaimMetadata() {
        var id=seed(tenant); var limited=new OutboxStore(pool,settings(List.of(tenant),1,1,1));
        var claim=limited.claim(tenant,1).getFirst();
        var forged=new OutboxStore.Claim(claim.event(),claim.token(),0);
        assertThat(limited.finish(forged,PublicationOutcome.RETRYABLE,Duration.ofMillis(10))).isTrue();
        assertThat(row(id)).containsEntry("state","FAILED").containsEntry("failure_code","EXHAUSTED");
    }

    @Test void tenantAuthorizationAndPoolContextFailClosed() {
        var other=UUID.randomUUID(); var own=seed(tenant); var foreign=seed(other);
        var jdbc=new JdbcTemplate(pool);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.outbox_event",Integer.class)).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> store.claim(other,1)).withMessage("Tenant is not authorized for this worker");
        var claim=store.claim(tenant,1).getFirst();
        assertThat(claim.event().eventId()).isEqualTo(own);
        var fake=new OutboxStore.Claim(new Publication(foreign,tenant,UUID.randomUUID(),"notification.created",1,"{}"),claim.token(),1);
        var before=row(foreign);
        assertThat(store.finish(fake,PublicationOutcome.ACKNOWLEDGED,Duration.ofMillis(10))).isFalse();
        assertThat(row(foreign)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.outbox_event",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT NULLIF(current_setting('app.tenant_id',true),'')",String.class)).isNull();
    }

    @Test void restrictedRoleCannotMutateIdentityUnrelatedDataOrPolicies() {
        OutboxConfiguration.verifyRole(new JdbcTemplate(pool));
        seed(tenant);
        var jdbc=new JdbcTemplate(pool);
        for(var sql:List.of("UPDATE app.outbox_event SET tenant_id=gen_random_uuid()", "UPDATE app.outbox_event SET payload='changed'",
                "UPDATE app.outbox_event SET created_by='changed'", "DELETE FROM app.outbox_event",
                "UPDATE app.recipient SET active=false", "UPDATE app.notification SET body='changed'",
                "SELECT * FROM app.flyway_schema_history", "ALTER TABLE app.outbox_event DISABLE ROW LEVEL SECURITY",
                "CREATE TABLE app.forbidden (id integer)", "SET ROLE crud_owner")) {
            assertThatThrownBy(() -> jdbc.execute(sql)).as(sql).hasRootCauseInstanceOf(SQLException.class)
                    .rootCause().extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("42501");
        }
    }

    @Test void invalidStateCombinationsRollbackAndContextClearsAfterFailure() {
        var id=seed(tenant); var before=row(id);
        var tx=new TransactionTemplate(new JdbcTransactionManager(pool)); var jdbc=new JdbcTemplate(pool);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
            jdbc.update("UPDATE app.outbox_event SET state='PROCESSING' WHERE id=?",id);
        })).hasRootCauseInstanceOf(SQLException.class).rootCause()
                .extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("23514");
        assertThat(row(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.outbox_event",Integer.class)).isZero();
    }

    @Test void tenantLeadingEligibilityIndexesAreUsable() throws Exception {
        admin.execute("""
                INSERT INTO app.outbox_event(id,tenant_id,version,created_at,updated_at,created_by,updated_by,aggregate_id,event_type,schema_version,payload)
                SELECT gen_random_uuid(), gen_random_uuid(),0,now(),now(),'seed','seed',gen_random_uuid(),'event',1,'{}'
                FROM generate_series(1,5000)
                """);
        seed(tenant); admin.execute("ANALYZE app.outbox_event");
        var plan=admin.queryForList("""
                EXPLAIN SELECT id, aggregate_id, event_type, schema_version, payload, attempt_count
                FROM app.outbox_event WHERE tenant_id=? AND (
                    state='PENDING' OR (state='RETRY' AND next_attempt_at <= statement_timestamp())
                    OR (state='PROCESSING' AND lease_until <= statement_timestamp()))
                ORDER BY created_at,id LIMIT 4 FOR UPDATE SKIP LOCKED
                """,String.class,tenant);
        assertThat(String.join("\n",plan)).contains("outbox_due_idx");
        var path=Path.of("build/reports/outbox/claim-plan.txt"); Files.createDirectories(path.getParent()); Files.write(path,plan);
    }
    private OutboxWorker worker(OutboxSettings config, OutboxPublisher adapter,
                                io.micrometer.core.instrument.MeterRegistry metrics) {
        return new OutboxWorker(new OutboxStore(pool,config),adapter,config,metrics,() -> {});
    }

    @Test void workerPublishesOutsideTransactionsAndRecoversLostAcknowledgment() {
        var id=seed(tenant); var accepted=new java.util.concurrent.CopyOnWriteArrayList<Publication>();
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboxPublisher adapter=(event,timeout) -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            accepted.add(event);
            return accepted.size()==1 ? PublicationOutcome.UNKNOWN : PublicationOutcome.ACKNOWLEDGED;
        };
        try(var worker=worker(settings,adapter,metrics)) {
            worker.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(row(id)).containsEntry("state","PUBLISHED"));
            assertThat(accepted).hasSize(2);
            assertThat(accepted.get(0)).isEqualTo(accepted.get(1));
            assertThat(accepted.getFirst().eventId()).isEqualTo(id);
            assertThat(metrics.get("outbox.attempts").tag("outcome","UNKNOWN").counter().count()).isEqualTo(1);
            assertThat(metrics.get("outbox.attempts").tag("outcome","ACKNOWLEDGED").counter().count()).isEqualTo(1);
            assertThat(row(id)).containsEntry("created_by","producer:original").containsEntry("updated_by",OutboxStore.ACTOR);
            assertThat(metrics.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).noneMatch(tag -> List.of("tenant","eventId","recipient").contains(tag.getKey())));
        }
    }

    @Test void adapterExceptionsBecomeUnknownWithoutLeakingSensitiveErrors(org.springframework.boot.test.system.CapturedOutput output) {
        var id=seed(tenant); var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var once=settings(List.of(tenant),1,1,1);
        try(var worker=worker(once,(event,timeout) -> { throw new java.io.IOException("SECRET_PAYLOAD_TOKEN"); },metrics)) {
            worker.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(row(id)).containsEntry("state","FAILED").containsEntry("failure_code","EXHAUSTED"));
            assertThat(row(id).values().toString()).doesNotContain("SECRET_PAYLOAD_TOKEN");
            assertThat(output.getAll()).doesNotContain("SECRET_PAYLOAD_TOKEN");
        }
    }

    @Test void timeoutKeepsCapacityUntilActualTransportExits() throws Exception {
        var first=seed(tenant); var second=seed(tenant);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var config=settings(List.of(tenant),1,1,3);
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try(var worker=worker(config,(event,timeout) -> {
            entered.countDown();
            while(release.getCount()>0) {
                try { release.await(); } catch(InterruptedException _) { /* deliberate broken adapter */ }
            }
            return PublicationOutcome.ACKNOWLEDGED;
        },metrics)) {
            worker.start(); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(metrics.get("outbox.attempts").tag("outcome","UNKNOWN").counter().count()).isEqualTo(1));
            for(int i=0;i<10;i++) worker.pollOnce();
            assertThat(((Number)row(first).get("attempt_count")).intValue()
                    + ((Number)row(second).get("attempt_count")).intValue()).isEqualTo(1);
            assertThat(row(first).get("published_at")).isNull(); assertThat(row(second).get("published_at")).isNull();
            long start=System.nanoTime(); worker.stop();
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            worker.pollOnce();
            assertThat(worker.isRunning()).isFalse();
        } finally { release.countDown(); }
    }

    @Test void tenantRoundRobinDoesNotSkipWaitingTenantWhenCapacityIsBusy() throws Exception {
        var other=UUID.randomUUID();
        for(int i=0;i<5;i++) seed(tenant);
        var otherId=seed(other);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var seen=new CopyOnWriteArrayList<UUID>();
        var config=settings(List.of(tenant,other),1,1,3);
        try(var worker=worker(config,(event,timeout) -> {
            seen.add(event.tenantId());
            if(seen.size()==1) { entered.countDown(); release.await(); }
            return PublicationOutcome.ACKNOWLEDGED;
        },new io.micrometer.core.instrument.simple.SimpleMeterRegistry())) {
            worker.start(); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<10;i++) worker.pollOnce();
            release.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(row(otherId)).containsEntry("state","PUBLISHED"));
            assertThat(seen.subList(0,2)).containsExactly(tenant,other);
        } finally { release.countDown(); }
    }

    @Test void gracefulShutdownLeavesInterruptedAttemptRecoverable() throws Exception {
        var id=seed(tenant); var entered=new CountDownLatch(1);
        try(var worker=worker(settings,(event,timeout) -> {
            entered.countDown(); new CountDownLatch(1).await(); return PublicationOutcome.ACKNOWLEDGED;
        },new io.micrometer.core.instrument.simple.SimpleMeterRegistry())) {
            worker.start(); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); worker.stop();
            assertThat(row(id).get("published_at")).isNull();
            var state=(String)row(id).get("state");
            if(state.equals("PROCESSING")) expire(id); else due(id);
            assertThat(store.claim(tenant,1)).hasSize(1);
        }
    }

    @Test void singleConnectionReusedAcrossTenantsAndRollbackDoesNotLeak() {
        var config=new HikariConfig(); config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername("crud_outbox"); config.setPassword("test-worker-only"); config.setMaximumPoolSize(1);
        var other=UUID.randomUUID(); var id=seed(tenant); var foreign=seed(other);
        try(var single=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(single); var backend=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
            var scoped=new OutboxStore(single,settings(List.of(tenant,other),1,1,3));
            assertThat(scoped.claim(tenant,1)).extracting(c -> c.event().eventId()).containsExactly(id);
            assertThat(scoped.claim(other,1)).extracting(c -> c.event().eventId()).containsExactly(foreign);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.outbox_event",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT NULLIF(current_setting('app.tenant_id',true),'')",String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)).isEqualTo(backend);
        }
    }

    @Test void finalizationRechecksLeaseAfterWaitingForRowLock() throws Exception {
        var id=seed(tenant); var claim=store.claim(tenant,1).getFirst();
        try(var connection=POSTGRES.createConnection(""); var executor=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("UPDATE app.outbox_event SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?")) {
                statement.setObject(1,id); statement.executeUpdate();
            }
            var completion=executor.submit(() -> store.finish(claim,PublicationOutcome.ACKNOWLEDGED,Duration.ofMillis(10)));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
                    admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename='crud_outbox' AND wait_event_type='Lock'",Integer.class)>0);
            connection.commit();
            var expired=row(id);
            assertThat(completion.get(5,TimeUnit.SECONDS)).isFalse();
            assertThat(row(id)).isEqualTo(expired);
        }
    }

    @Test void metricsDescribeBacklogAndPublishedEventsAreNotClaimedAgain() {
        var id=seed(tenant);
        assertThat(store.backlog(tenant).states()).containsEntry("PENDING",1L);
        assertThat(store.claim(tenant,1)).hasSize(1);
        expire(id);
        assertThat(store.backlog(tenant).expiredLeases()).isEqualTo(1);
        assertThat(store.backlog(tenant).oldestSeconds()).isGreaterThanOrEqualTo(0);
        var recovered=store.claim(tenant,1).getFirst();
        assertThat(store.finish(recovered,PublicationOutcome.ACKNOWLEDGED,Duration.ofMillis(10))).isTrue();
        assertThat(store.claim(tenant,1)).isEmpty();
        assertThat(store.backlog(tenant).states()).containsEntry("PUBLISHED",1L);
        assertThat(store.backlog(tenant).oldestSeconds()).isZero();
    }

    @Test void startupRejectsExcessColumnGrantsAndMissingReadPrivilege() {
        var jdbc=new JdbcTemplate(pool);
        for(var privilege:List.of("INSERT (payload)","REFERENCES (id)","TRIGGER")) {
            admin.execute("GRANT "+privilege+" ON app.outbox_event TO crud_outbox");
            try {
                assertThatIllegalStateException().isThrownBy(() -> OutboxConfiguration.verifyRole(jdbc))
                        .withMessage("Worker database role is not restricted");
            } finally { admin.execute("REVOKE "+privilege+" ON app.outbox_event FROM crud_outbox"); }
        }
        admin.execute("REVOKE SELECT ON app.outbox_event FROM crud_outbox");
        try {
            assertThatIllegalStateException().isThrownBy(() -> OutboxConfiguration.verifyRole(jdbc))
                    .withMessage("Worker database role is not restricted");
        } finally { admin.execute("GRANT SELECT ON app.outbox_event TO crud_outbox"); }
    }

    @Test void databaseFailureAfterAcceptancePreservesClaimForRecovery() {
        var id=seed(tenant); var original=store.claim(tenant,1).getFirst();
        var accepted=new ArrayList<Publication>(); accepted.add(original.event());
        var before=row(id);
        admin.execute("ALTER TABLE app.outbox_event ADD CONSTRAINT simulated_completion_failure CHECK (state <> 'PUBLISHED')");
        try {
            assertThatThrownBy(() -> store.finish(original,PublicationOutcome.ACKNOWLEDGED,Duration.ofMillis(10)))
                    .hasRootCauseInstanceOf(SQLException.class).rootCause()
                    .extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("23514");
            assertThat(row(id)).isEqualTo(before);
        } finally { admin.execute("ALTER TABLE app.outbox_event DROP CONSTRAINT simulated_completion_failure"); }
        expire(id);
        var recovered=new OutboxStore(pool,settings).claim(tenant,1).getFirst();
        accepted.add(recovered.event());
        assertThat(accepted.get(1)).isEqualTo(accepted.get(0));
        assertThat(store.finish(recovered,PublicationOutcome.ACKNOWLEDGED,Duration.ofMillis(10))).isTrue();
        assertThat(row(id)).containsEntry("state","PUBLISHED").containsEntry("created_by","producer:original");
    }

}
