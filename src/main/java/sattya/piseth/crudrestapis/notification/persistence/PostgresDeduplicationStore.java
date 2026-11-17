package sattya.piseth.crudrestapis.notification.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;
import sattya.piseth.crudrestapis.notification.application.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * ON CONFLICT waits for competing transactions without aborting this transaction.
 * The separate SELECT gets a fresh READ COMMITTED snapshot after that wait.
 */
@Component
@RequiredArgsConstructor
public class PostgresDeduplicationStore implements DeduplicationStore {
    private static final String CONSUMER_PARAMETER = "consumer";
    private static final String EVENT_PARAMETER = "event";

    private final EntityManager entityManager;
    private final AuditorAware<String> auditorAware;
    private final Clock clock;

    @Override
    public Reservation reserveRequest(ProducerScope source, String key, String fingerprint, UUID candidateId) {
        var insert = entityManager.createNativeQuery("""
                INSERT INTO app.ingestion_request
                    (id, tenant_id, producer_id, idempotency_key, request_hash, notification_id,
                     version, created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenant, :producer, :key, :hash, :notification,
                        0, :now, :now, :actor, :actor)
                ON CONFLICT (tenant_id, producer_id, idempotency_key) DO NOTHING
                """).setParameter("key", key);
        if (bindInsert(insert, source, fingerprint, candidateId).executeUpdate() == 1) {
            return new Reservation(candidateId, true);
        }
        var existing = entityManager.createNativeQuery("""
                SELECT request_hash, notification_id FROM app.ingestion_request
                WHERE tenant_id = :tenant AND producer_id = :producer AND idempotency_key = :key
                """).setParameter("key", key);
        return existingReservation(bindSource(existing, source), fingerprint);
    }

    @Override
    public Reservation reserveEvent(ProducerScope source, String consumer, UUID eventId,
                                    String fingerprint, UUID candidateId) {
        var insert = entityManager.createNativeQuery("""
                INSERT INTO app.processed_event
                    (id, tenant_id, producer_id, consumer_name, event_id, request_hash, notification_id,
                     version, created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenant, :producer, :consumer, :event, :hash, :notification,
                        0, :now, :now, :actor, :actor)
                ON CONFLICT (tenant_id, producer_id, consumer_name, event_id) DO NOTHING
                """).setParameter(CONSUMER_PARAMETER, consumer).setParameter(EVENT_PARAMETER, eventId);
        if (bindInsert(insert, source, fingerprint, candidateId).executeUpdate() == 1) {
            return new Reservation(candidateId, true);
        }
        var existing = entityManager.createNativeQuery("""
                SELECT request_hash, notification_id FROM app.processed_event
                WHERE tenant_id = :tenant AND producer_id = :producer
                    AND consumer_name = :consumer AND event_id = :event
                """).setParameter(CONSUMER_PARAMETER, consumer).setParameter(EVENT_PARAMETER, eventId);
        return existingReservation(bindSource(existing, source), fingerprint);
    }

    @Override
    public void completeEvent(ProducerScope source, String consumer, UUID eventId, UUID notificationId) {
        var query = entityManager.createNativeQuery("""
                UPDATE app.processed_event SET notification_id = :notification,
                    version = version + 1, updated_at = :now, updated_by = :actor
                WHERE tenant_id = :tenant AND producer_id = :producer
                    AND consumer_name = :consumer AND event_id = :event
                """)
                .setParameter("notification", notificationId)
                .setParameter(CONSUMER_PARAMETER, consumer).setParameter(EVENT_PARAMETER, eventId)
                .setParameter("now", now()).setParameter("actor", currentActor());
        if (bindSource(query, source).executeUpdate() != 1) {
            throw new IllegalStateException("Event reservation is missing");
        }
    }

    private Query bindInsert(Query query, ProducerScope source, String fingerprint, UUID candidateId) {
        // Native SQL does not run JPA entity listeners; set all audit fields explicitly.
        return bindSource(query, source).setParameter("id", UUID.randomUUID())
                .setParameter("hash", fingerprint).setParameter("notification", candidateId)
                .setParameter("now", now()).setParameter("actor", currentActor());
    }

    private Query bindSource(Query query, ProducerScope source) {
        return query.setParameter("tenant", source.tenantId()).setParameter("producer", source.producerId());
    }

    private Reservation existingReservation(Query query, String fingerprint) {
        var row = (Object[]) query.getSingleResult();
        if (!fingerprint.equals(row[0])) {
            throw new IdempotencyConflictException();
        }
        return new Reservation((UUID) row[1], false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private String currentActor() {
        return auditorAware.getCurrentAuditor()
                .orElseThrow(() -> new IllegalStateException("An audit actor is required"));
    }
}
