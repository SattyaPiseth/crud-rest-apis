package sattya.piseth.crudrestapis.outbox.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sattya.piseth.crudrestapis.shared.domain.AuditedEntity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "outbox_event")
public class OutboxEvent extends AuditedEntity {
    @Column(nullable = false, updatable = false)
    private UUID aggregateId;
    @Column(nullable = false, updatable = false, length = 100)
    private String eventType;
    @Column(nullable = false, updatable = false)
    private int schemaVersion;
    /** Serialized event; producers must use a serializer, never string concatenation. */
    @Column(nullable = false, updatable = false, length = 20000)
    private String payload;
    private Instant publishedAt;

    public OutboxEvent(UUID tenantId, UUID aggregateId, String eventType, int schemaVersion, String payload) {
        Objects.requireNonNull(aggregateId, "aggregateId");
        eventType = text(eventType, "eventType", 100);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        payload = text(payload, "payload", 20000);
        super(tenantId);
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
    }

    public void markPublished(Instant when) {
        Objects.requireNonNull(when, "when");
        if (publishedAt == null) {
            publishedAt = when;
        }
    }
}
