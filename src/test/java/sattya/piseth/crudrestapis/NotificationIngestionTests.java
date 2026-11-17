package sattya.piseth.crudrestapis;

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
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import sattya.piseth.crudrestapis.shared.audit.BackgroundActor;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.schemas=app",
    "spring.flyway.default-schema=app",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class NotificationIngestionTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-trixie");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired NotificationIngestionService ingestion;
    @Autowired NotificationEventConsumer consumer;
    @Autowired BackgroundActor actor;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void sameRequestReturnsOriginalReceiptAndCanonicalizesRecipientOrder() {
        var source = source();
        var alice = recipient(source.tenantId());
        var bob = recipient(source.tenantId());
        var first = request(source, "order-123", command("Title", List.of(alice, bob)));
        var retry = request(source, "order-123", command("Title", List.of(bob, alice, alice)));

        assertThat(first.replayed()).isFalse();
        assertThat(retry).isEqualTo(new IngestionResult(first.notificationId(), true));
        assertCounts(source, 1, 2, 1, 1, 0);
        assertThat(jdbc.queryForObject("""
                SELECT created_by FROM app.ingestion_request WHERE tenant_id = ?
                """, String.class, source.tenantId())).isEqualTo("service:producer-test");
        var payload = jdbc.queryForObject("SELECT payload FROM app.outbox_event WHERE tenant_id = ?",
                String.class, source.tenantId());
        assertThat(objectMapper.readTree(payload).get("occurredAt").asString()).endsWith("+07:00");
    }

    @Test
    void sameKeyWithChangedBodyConflictsAndPreservesOriginalResult() {
        var source = source();
        var recipients = List.of(recipient(source.tenantId()));
        var original = request(source, "key", command("Original", recipients));
        var changed = command("Changed", recipients);
        assertThatThrownBy(() -> request(source, "key", changed))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(request(source, "key", command("Original", recipients)).notificationId())
                .isEqualTo(original.notificationId());
        assertCounts(source, 1, 1, 1, 1, 0);
    }

    @Test
    void keysAreScopedByTenantAndProducer() {
        var firstSource = source();
        var otherProducer = new ProducerScope(firstSource.tenantId(), "another-producer");
        var otherTenant = new ProducerScope(UUID.randomUUID(), firstSource.producerId());
        var command = command("Title", List.of(recipient(firstSource.tenantId())));
        var first = request(firstSource, "shared-key", command);
        var second = request(otherProducer, "shared-key", command);
        var third = request(otherTenant, "shared-key",
                command("Title", List.of(recipient(otherTenant.tenantId()))));
        assertThat(List.of(first.notificationId(), second.notificationId(), third.notificationId()))
                .doesNotHaveDuplicates();
        assertCounts(firstSource, 2, 2, 2, 2, 0);
        assertCounts(otherTenant, 1, 1, 1, 1, 0);
    }

    @Test
    void consumerRedeliveryIsDeduplicatedAndChangedEventIsRejected() {
        var source = source();
        var command = command("Title", List.of(recipient(source.tenantId())));
        var event = new NotificationRequestedEvent(UUID.randomUUID(), 1, "key", command);
        var first = consumer.consume(source, event);
        assertThat(consumer.consume(source, event)).isEqualTo(new IngestionResult(first.notificationId(), true));
        var changed = new NotificationRequestedEvent(event.eventId(), 1, "different-key", command);
        assertThatThrownBy(() -> consumer.consume(source, changed))
                .isInstanceOf(IdempotencyConflictException.class);
        assertCounts(source, 1, 1, 1, 1, 1);
        assertThat(jdbc.queryForObject("SELECT created_by FROM app.processed_event WHERE tenant_id = ?",
                String.class, source.tenantId())).isEqualTo("service:notification-ingestion-v1");
        assertThat(actor.currentActor()).isEmpty();
    }

    @Test
    void differentEventsWithSameRequestKeyShareTheOriginalNotification() {
        var source = source();
        var command = command("Title", List.of(recipient(source.tenantId())));
        var first = consumer.consume(source, new NotificationRequestedEvent(UUID.randomUUID(), 1, "key", command));
        var second = consumer.consume(source, new NotificationRequestedEvent(UUID.randomUUID(), 1, "key", command));
        assertThat(second).isEqualTo(new IngestionResult(first.notificationId(), true));
        assertCounts(source, 1, 1, 1, 1, 2);
        assertThat(jdbc.queryForList("SELECT notification_id FROM app.processed_event WHERE tenant_id = ?",
                UUID.class, source.tenantId())).containsOnly(first.notificationId());
    }

    @Test
    void failedConsumerDoesNotPoisonEventIdOrIdempotencyKey() {
        var source = source();
        var eventId = UUID.randomUUID();
        var invalid = new NotificationRequestedEvent(eventId, 1, "key",
                command("Title", List.of(UUID.randomUUID())));
        assertThatThrownBy(() -> consumer.consume(source, invalid))
                .isInstanceOf(IllegalArgumentException.class);
        assertCounts(source, 0, 0, 0, 0, 0);
        var valid = new NotificationRequestedEvent(eventId, 1, "key",
                command("Title", List.of(recipient(source.tenantId()))));
        assertThat(consumer.consume(source, valid).replayed()).isFalse();
        assertCounts(source, 1, 1, 1, 1, 1);
    }

    @Test
    void crossTenantAndInactiveRecipientsAreRejectedWithoutPartialWrites() {
        var source = source();
        var foreignId = recipient(UUID.randomUUID());
        var foreignCommand = command("Title", List.of(foreignId));
        assertThatThrownBy(() -> request(source, "foreign", foreignCommand))
                .isInstanceOf(IllegalArgumentException.class);
        var inactive = recipient(source.tenantId());
        actor.callAs("seed", () -> new TransactionTemplate(transactionManager).execute(status -> {
            entityManager.find(Recipient.class, inactive).deactivate();
            return null;
        }));
        var inactiveCommand = command("Title", List.of(inactive));
        assertThatThrownBy(() -> request(source, "inactive", inactiveCommand))
                .isInstanceOf(IllegalArgumentException.class);
        assertCounts(source, 0, 0, 0, 0, 0);
    }

    @Test
    void databaseFailureAfterBusinessWritesRollsBackBothLedgersAndCanBeRetried() {
        var source = source();
        var event = new NotificationRequestedEvent(UUID.randomUUID(), 1, "key",
                command("Title", List.of(recipient(source.tenantId()))));
        // Test-only fault injection into this disposable database; no production data is touched.
        jdbc.execute("ALTER TABLE app.outbox_event ADD CONSTRAINT injected_outbox_failure CHECK (tenant_id <> '"
                + source.tenantId() + "'::uuid)");
        try {
            assertThatThrownBy(() -> consumer.consume(source, event))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
        } finally {
            jdbc.execute("ALTER TABLE app.outbox_event DROP CONSTRAINT injected_outbox_failure");
        }
        assertCounts(source, 0, 0, 0, 0, 0);
        assertThat(consumer.consume(source, event).replayed()).isFalse();
        assertCounts(source, 1, 1, 1, 1, 1);
    }

    @Test
    void concurrentRequestsCommitExactlyOneNotification() throws Exception {
        var source = source();
        var command = command("Title", List.of(recipient(source.tenantId())));
        var results = concurrently(() -> request(source, "concurrent-key", command));
        assertOneWinner(results);
        assertCounts(source, 1, 1, 1, 1, 0);
    }

    @Test
    void concurrentConsumerRedeliveriesCommitExactlyOneNotification() throws Exception {
        var source = source();
        var event = new NotificationRequestedEvent(UUID.randomUUID(), 1, "key",
                command("Title", List.of(recipient(source.tenantId()))));
        var results = concurrently(() -> consumer.consume(source, event));
        assertOneWinner(results);
        assertCounts(source, 1, 1, 1, 1, 1);
    }

    @Test
    void concurrentKeyReuseWithDifferentPayloadsHasOneWinnerAndFiveConflicts() throws Exception {
        var source = source();
        var recipients = List.of(recipient(source.tenantId()));
        var sequence = new AtomicInteger();
        var outcomes = concurrently(() -> {
            try {
                request(source, "key", command("Title " + sequence.incrementAndGet(), recipients));
                return "accepted";
            } catch (IdempotencyConflictException _) {
                return "conflict";
            }
        });
        assertThat(outcomes.stream().filter("accepted"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("conflict"::equals).count()).isEqualTo(5);
        assertCounts(source, 1, 1, 1, 1, 0);
    }

    @Test
    void waitingRequestSucceedsWhenTheOriginalReservationRollsBack() throws Exception {
        var source = source();
        var command = command("Title", List.of(recipient(source.tenantId())));
        try (var connection = jdbc.getDataSource().getConnection();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO app.ingestion_request
                            (id, tenant_id, producer_id, idempotency_key, request_hash, notification_id,
                             version, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?, ?, 'key', ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test')
                        """)) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, source.tenantId());
                    statement.setString(3, source.producerId());
                    statement.setString(4, "0".repeat(64));
                    statement.setObject(5, UUID.randomUUID());
                    statement.executeUpdate();
                }
                var waiting = executor.submit(() -> request(source, "key", command));
                await().alias("duplicate request waits on the uncommitted unique key")
                        .pollInterval(20, TimeUnit.MILLISECONDS)
                        .atMost(10, TimeUnit.SECONDS)
                        .until(() -> jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock'
                              AND query ILIKE '%INSERT INTO app.ingestion_request%')
                            """, Boolean.class));
                connection.rollback();
                assertThat(waiting.get(30, TimeUnit.SECONDS).replayed()).isFalse();
            } finally {
                // Release the blocker even if the assertion fails, before closing the executor.
                connection.rollback();
            }
        }
        assertCounts(source, 1, 1, 1, 1, 0);
    }

    @Test
    void expiredNewRequestFailsButCommittedReceiptRemainsReplayableAfterDeactivation() {
        var source = source();
        var id = recipient(source.tenantId());
        var expired = new IngestNotification("TYPE", "CATEGORY", "Title", "Body",
                Instant.parse("2020-01-01T00:00:00Z"), List.of(id));
        assertThatThrownBy(() -> request(source, "expired", expired)).isInstanceOf(IllegalArgumentException.class);
        var valid = command("Title", List.of(id));
        var result = request(source, "valid", valid);
        actor.callAs("seed", () -> new TransactionTemplate(transactionManager).execute(status -> {
            entityManager.find(Recipient.class, id).deactivate();
            return null;
        }));
        assertThat(request(source, "valid", valid)).isEqualTo(new IngestionResult(result.notificationId(), true));
        assertCounts(source, 1, 1, 1, 1, 0);
    }

    private ProducerScope source() {
        return new ProducerScope(UUID.randomUUID(), "orders-service");
    }

    private UUID recipient(UUID tenant) {
        return actor.callAs("seed", () -> new TransactionTemplate(transactionManager).execute(status -> {
            var recipient = new Recipient(tenant, "https://identity.example", UUID.randomUUID().toString());
            entityManager.persist(recipient);
            return recipient.getId();
        }));
    }

    private IngestNotification command(String title, List<UUID> recipients) {
        return new IngestNotification("ORDER_SHIPPED", "ORDERS", title, "Your order has shipped.", null, recipients);
    }

    private IngestionResult request(ProducerScope source, String key, IngestNotification command) {
        return actor.callAs("producer-test", () -> ingestion.ingest(source, key, command));
    }

    private void assertCounts(ProducerScope source, long notifications, long inbox, long outbox,
                              long requests, long events) {
        assertThat(count("notification", source.tenantId())).isEqualTo(notifications);
        assertThat(count("notification_recipient", source.tenantId())).isEqualTo(inbox);
        assertThat(count("outbox_event", source.tenantId())).isEqualTo(outbox);
        assertThat(count("ingestion_request", source.tenantId())).isEqualTo(requests);
        assertThat(count("processed_event", source.tenantId())).isEqualTo(events);
    }

    private long count(String table, UUID tenant) {
        // Table names are constants from assertCounts, never input.
        return jdbc.queryForObject("SELECT count(*) FROM app." + table + " WHERE tenant_id = ?", Long.class, tenant);
    }

    private <T> List<T> concurrently(Supplier<T> operation) throws Exception {
        var start = new CyclicBarrier(6);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>();
            for (int index = 0; index < 6; index++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return operation.get();
                }));
            }
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(40, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private void assertOneWinner(List<IngestionResult> results) {
        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().map(IngestionResult::notificationId).distinct().count()).isEqualTo(1);
    }
}
