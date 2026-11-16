package sattya.piseth.crudrestapis.notification.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sattya.piseth.crudrestapis.inbox.domain.InboxItem;
import sattya.piseth.crudrestapis.notification.domain.Notification;
import sattya.piseth.crudrestapis.outbox.domain.OutboxEvent;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

/**
 * Trusted application entry point; adapters must authorize ProducerScope before calling.
 * Each call owns a fresh transaction. No network calls occur inside it.
 */
@Service
@RequiredArgsConstructor
public class NotificationIngestionService {
    private final EntityManager entityManager;
    private final DeduplicationStore deduplication;
    private final RequestFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 30)
    public IngestionResult ingest(ProducerScope source, String idempotencyKey, IngestNotification command) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(command, "command");
        var key = text(idempotencyKey, "idempotencyKey", 200);
        return ingestInTransaction(source, key, command, candidate(source, command));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 30)
    public IngestionResult consume(ProducerScope source, NotificationRequestedEvent event) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(event, "event");
        var notification = candidate(source, event.command());
        var reservation = deduplication.reserveEvent(source, NotificationEventConsumer.CONSUMER_NAME,
                event.eventId(), fingerprint.forEvent(event), notification.getId());
        if (!reservation.acquired()) {
            return new IngestionResult(reservation.notificationId(), true);
        }
        var result = ingestInTransaction(source, event.idempotencyKey(), event.command(), notification);
        deduplication.completeEvent(source, NotificationEventConsumer.CONSUMER_NAME,
                event.eventId(), result.notificationId());
        entityManager.flush();
        return result;
    }

    private IngestionResult ingestInTransaction(ProducerScope source, String key,
                                                IngestNotification command, Notification notification) {
        var reservation = deduplication.reserveRequest(source, key, fingerprint.forCommand(command),
                notification.getId());
        if (!reservation.acquired()) {
            return new IngestionResult(reservation.notificationId(), true);
        }
        // Replays return the original receipt even if recipients or expiry changed since acceptance.
        if (notification.isExpiredAt(clock.instant())) {
            throw new IllegalArgumentException("Cannot ingest an expired notification");
        }
        var recipients = entityManager.createQuery("""
                SELECT r FROM Recipient r WHERE r.tenantId = :tenant
                    AND r.id IN :ids AND r.active = true ORDER BY r.id
                """, Recipient.class)
                .setParameter("tenant", source.tenantId())
                .setParameter("ids", command.recipientIds())
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .getResultList();
        if (recipients.size() != command.recipientIds().size()) {
            throw new IllegalArgumentException("Recipients must exist, be active, and belong to the authorized tenant");
        }
        entityManager.persist(notification);
        recipients.stream().map(recipient -> new InboxItem(notification, recipient))
                .forEach(entityManager::persist);
        var payload = objectMapper.writeValueAsString(
                new NotificationCreated(notification.getId(), recipients.size(), clock.instant()));
        entityManager.persist(new OutboxEvent(source.tenantId(), notification.getId(),
                "notification.created", 1, payload));
        entityManager.flush();
        return new IngestionResult(notification.getId(), false);
    }

    private Notification candidate(ProducerScope source, IngestNotification command) {
        return new Notification(source.tenantId(), command.type(), command.category(),
                command.title(), command.body(), command.expiresAt());
    }

    private record NotificationCreated(UUID notificationId, int recipientCount, Instant occurredAt) {}
}
