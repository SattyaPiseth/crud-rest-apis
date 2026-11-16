package sattya.piseth.crudrestapis.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sattya.piseth.crudrestapis.shared.domain.AuditedEntity;
import java.time.Instant;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification", uniqueConstraints =
    @UniqueConstraint(columnNames = {"tenant_id", "id"}))
public class Notification extends AuditedEntity {
    @Column(nullable = false, updatable = false, length = 100)
    private String type;
    @Column(nullable = false, updatable = false, length = 100)
    private String category;
    @Column(nullable = false, updatable = false, length = 200)
    private String title;
    @Column(nullable = false, updatable = false, length = 10000)
    private String body;
    @Column(updatable = false)
    private Instant expiresAt;

    public Notification(UUID tenantId, String type, String category,
                        String title, String body, Instant expiresAt) {
        super(tenantId);
        this.type = text(type, "type", 100);
        this.category = text(category, "category", 100);
        this.title = text(title, "title", 200);
        this.body = text(body, "body", 10000);
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant instant) {
        java.util.Objects.requireNonNull(instant, "instant");
        return expiresAt != null && !expiresAt.isAfter(instant);
    }
}
