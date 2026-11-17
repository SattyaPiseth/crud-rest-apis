package sattya.piseth.crudrestapis;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sattya.piseth.crudrestapis.shared.audit.BackgroundActor;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import sattya.piseth.crudrestapis.notification.domain.Notification;
import sattya.piseth.crudrestapis.inbox.domain.InboxItem;
import sattya.piseth.crudrestapis.inbox.persistence.InboxRepository;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.schemas=app",
    "spring.flyway.default-schema=app",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class NotificationPersistenceTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-trixie");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @PersistenceContext
    EntityManager entityManager;
    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    BackgroundActor actor;
    @Autowired
    InboxRepository inbox;
    @Autowired
    Clock clock;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void applicationAndDatabaseSessionUsePhnomPenhTimezone() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Phnom_Penh"));
        transaction("timezone-test", () -> {
            assertThat(entityManager.createNativeQuery("SHOW TIME ZONE").getSingleResult())
                    .isEqualTo("Asia/Phnom_Penh");
            return null;
        });
    }

    @Test
    void timestampsRenderInPhnomPenhAndRoundTripWithoutShiftingTheInstant() {
        var instant = Instant.parse("2026-11-16T20:30:00.123456Z");
        var json = objectMapper.writeValueAsString(instant);
        assertThat(json).isEqualTo("\"2026-11-17T03:30:00.123456+07:00\"");
        assertThat(objectMapper.readValue(json, Instant.class)).isEqualTo(instant);
        var notification = new Notification(UUID.randomUUID(), "TYPE", "CATEGORY", "Title", "Body", instant);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(notification))
                .get("expiresAt").asString()).isEqualTo("2026-11-17T03:30:00.123456+07:00");
    }

    private <T> T transaction(String service, java.util.function.Supplier<T> operation) {
        // The actor scope encloses the transaction commit, including the final JPA flush.
        return actor.callAs(service, () ->
                new TransactionTemplate(transactionManager).execute(status -> operation.get()));
    }

    @Test
    void unauditedWriteFailsWithoutPersistingTheRecipient() {
        var recipient = new Recipient(UUID.randomUUID(), "issuer", "no-actor");
        var transactionTemplate = new TransactionTemplate(transactionManager);
        TransactionCallback<Void> unauditedWrite = status -> {
            entityManager.persist(recipient);
            entityManager.flush();
            return null;
        };
        assertThatThrownBy(() -> transactionTemplate.execute(unauditedWrite))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actor is required");
        transaction("reader", () -> {
            assertThat(entityManager.find(Recipient.class, recipient.getId())).isNull();
            return null;
        });
    }

    @Test
    void duplicateIdentityWithinTenantIsRejected() {
        var tenant = UUID.randomUUID();
        transaction("seed", () -> {
            entityManager.persist(new Recipient(tenant, "issuer", "duplicate"));
            return null;
        });
        var duplicate = new Recipient(tenant, "issuer", "duplicate");
        Supplier<Void> duplicateWrite = () -> {
            entityManager.persist(duplicate);
            entityManager.flush();
            return null;
        };
        assertThatThrownBy(() -> transaction("duplicate", duplicateWrite))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void recordsCreationAndUpdateWithoutChangingCreationActor() {
        var recipient = new Recipient(UUID.randomUUID(), "issuer", UUID.randomUUID().toString());
        transaction("creator", () -> { entityManager.persist(recipient); return null; });
        assertThat(recipient.getCreatedAt()).isNotNull();
        assertThat(recipient.getCreatedBy()).isEqualTo("service:creator");
        var createdAt = recipient.getCreatedAt();

        transaction("editor", () -> {
            var stored = entityManager.find(Recipient.class, recipient.getId());
            stored.deactivate();
            return null;
        });
        transaction("reader", () -> {
            var stored = entityManager.find(Recipient.class, recipient.getId());
            assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
            assertThat(stored.getCreatedBy()).isEqualTo("service:creator");
            assertThat(stored.getUpdatedBy()).isEqualTo("service:editor");
            assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(createdAt);
            assertThat(stored.getVersion()).isEqualTo(1L);
            return null;
        });
    }

    @Test
    void ownershipLookupDoesNotExposeAnotherRecipientsInbox() {
        transaction("test", () -> {
            var tenant = UUID.randomUUID();
            var recipient = new Recipient(tenant, "issuer", "alice");
            var notification = new Notification(tenant, "TYPE", "CATEGORY", "Title", "Body", null);
            var item = new InboxItem(notification, recipient);
            entityManager.persist(recipient);
            entityManager.persist(notification);
            inbox.save(item);
            entityManager.flush();
            assertThat(inbox.findByIdAndTenantIdAndRecipientId(item.getId(), tenant, recipient.getId())).isPresent();
            assertThat(inbox.findByIdAndTenantIdAndRecipientId(item.getId(), tenant, UUID.randomUUID())).isEmpty();
            assertThat(inbox.findByIdAndTenantIdAndRecipientId(item.getId(), UUID.randomUUID(), recipient.getId())).isEmpty();
            return null;
        });
    }

    @Test
    void databaseRejectsCrossTenantForeignKeyEvenWhenJavaIsBypassed() {
        var tenant = UUID.randomUUID();
        var recipient = new Recipient(tenant, "issuer", "alice");
        var notification = new Notification(tenant, "TYPE", "CATEGORY", "Title", "Body", null);
        transaction("seed", () -> {
            entityManager.persist(recipient);
            entityManager.persist(notification);
            return null;
        });
        Supplier<Integer> crossTenantWrite = () ->
            entityManager.createNativeQuery("""
                INSERT INTO app.notification_recipient
                    (id, tenant_id, version, created_at, updated_at, created_by, updated_by,
                     notification_id, recipient_id)
                VALUES (:id, :tenant, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test',
                        :notification, :recipient)
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("tenant", UUID.randomUUID())
                .setParameter("notification", notification.getId())
                .setParameter("recipient", recipient.getId())
                .executeUpdate();
        assertThatThrownBy(() -> transaction("test", crossTenantWrite))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void optimisticLockPreventsLostReadUpdate() {
        var tenant = UUID.randomUUID();
        var recipient = new Recipient(tenant, "issuer", "bob");
        var notification = new Notification(tenant, "TYPE", "CATEGORY", "Title", "Body", null);
        var item = new InboxItem(notification, recipient);
        transaction("seed", () -> {
            entityManager.persist(recipient);
            entityManager.persist(notification);
            entityManager.persist(item);
            return null;
        });
        try (var first = entityManagerFactory.createEntityManager();
             var second = entityManagerFactory.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            var firstCopy = first.find(InboxItem.class, item.getId());
            var secondCopy = second.find(InboxItem.class, item.getId());
            firstCopy.markRead(Instant.now());
            secondCopy.markRead(Instant.now().plusSeconds(1));
            actor.callAs("first", () -> { first.getTransaction().commit(); return null; });
            var secondTransaction = second.getTransaction();
            Supplier<Void> staleCommit = () -> {
                secondTransaction.commit();
                return null;
            };
            assertThatThrownBy(() -> actor.callAs("second", staleCommit))
                    .isInstanceOf(RollbackException.class);
        }
    }
}
