package sattya.piseth.crudrestapis.outbox.publication;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.outbox.enabled=true","spring.flyway.enabled=false"})
@Testcontainers
@Import(OutboxEnabledApplicationTests.Adapter.class)
class OutboxEnabledApplicationTests {
    static final UUID TENANT=UUID.randomUUID();
    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.7-trixie");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        var admin=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        admin.execute("CREATE ROLE crud_owner NOLOGIN");
        admin.execute("CREATE SCHEMA app AUTHORIZATION crud_owner");
        admin.execute("CREATE ROLE crud_app LOGIN PASSWORD 'test-app-only' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA app TO crud_app");
        admin.execute("ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO crud_app");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas("app").defaultSchema("app").initSql("SET ROLE crud_owner").load().migrate();
        admin.execute("CREATE ROLE crud_outbox LOGIN PASSWORD 'test-worker-only' NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA app TO crud_outbox");
        admin.execute("GRANT SELECT ON app.outbox_event TO crud_outbox");
        admin.execute("GRANT UPDATE (state,attempt_count,next_attempt_at,claim_token,lease_until,failure_code,published_at,updated_at,updated_by,version) ON app.outbox_event TO crud_outbox");
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> "crud_app");
        properties.add("spring.datasource.password",() -> "test-app-only");
        properties.add("app.outbox.jdbc-url",POSTGRES::getJdbcUrl);
        properties.add("app.outbox.password",() -> "test-worker-only");
        properties.add("app.outbox.tenants",() -> TENANT.toString());
        properties.add("app.outbox.poll-interval",() -> "20ms");
    }
    @Autowired DataSource apiDataSource;
    @Autowired PlatformTransactionManager apiTransactions;
    @Autowired OutboxWorker worker;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired sattya.piseth.crudrestapis.shared.persistence.TenantDatabaseContext tenantContext;
    @Autowired sattya.piseth.crudrestapis.shared.audit.BackgroundActor actor;
    @Autowired sattya.piseth.crudrestapis.notification.application.NotificationIngestionService ingestion;
    @Autowired RecordingPublisher publisher;
    @org.junit.jupiter.api.AfterAll static void stopWorker(@Autowired OutboxWorker worker) { worker.stop(); }
    @Test void enabledWorkerPreservesApiDatasourceAndJpaTransactionManager() {
        assertThat(worker.isRunning()).isTrue();
        assertThat(apiTransactions).isInstanceOf(JpaTransactionManager.class);
        assertThat(new JdbcTemplate(apiDataSource).queryForObject("SELECT current_user",String.class)).isEqualTo("crud_app");
    }
    @Test void ingestionAndReplayPreserveAuditsWithActiveWorker() {
        var recipient=new sattya.piseth.crudrestapis.recipient.domain.Recipient(TENANT,"issuer","reader");
        actor.callAs("seed",() -> new org.springframework.transaction.support.TransactionTemplate(apiTransactions).execute(status -> {
            tenantContext.setTenant(TENANT); entityManager.persist(recipient); return null;
        }));
        var source=new sattya.piseth.crudrestapis.notification.application.ProducerScope(TENANT,"producer");
        var command=new sattya.piseth.crudrestapis.notification.application.IngestNotification(
                "TYPE","CATEGORY","Title","Body",null,java.util.List.of(recipient.getId()));
        var accepted=actor.callAs("integration-producer",() -> ingestion.ingest(source,"key",command));
        var admin=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(admin.queryForObject("SELECT state FROM app.outbox_event WHERE tenant_id=?",String.class,TENANT)).isEqualTo("PUBLISHED"));
        var replay=actor.callAs("integration-producer",() -> ingestion.ingest(source,"key",command));
        assertThat(replay.replayed()).isTrue(); assertThat(replay.notificationId()).isEqualTo(accepted.notificationId());
        assertThat(admin.queryForObject("SELECT count(*) FROM app.outbox_event WHERE tenant_id=?",Integer.class,TENANT)).isEqualTo(1);
        assertThat(admin.queryForMap("SELECT created_by,updated_by,version FROM app.outbox_event WHERE tenant_id=?",TENANT))
                .containsEntry("created_by","service:integration-producer").containsEntry("updated_by",OutboxStore.ACTOR).containsEntry("version",2L);
        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.getFirst().aggregateId()).isEqualTo(accepted.notificationId());
    }
    static class RecordingPublisher implements OutboxPublisher {
        final java.util.List<Publication> events=new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public PublicationOutcome publish(Publication event,java.time.Duration timeout) {
            events.add(event); return PublicationOutcome.ACKNOWLEDGED;
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Adapter {
        @Bean RecordingPublisher testOnlyAdapter() { return new RecordingPublisher(); }
    }
}
