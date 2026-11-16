package sattya.piseth.crudrestapis.inbox.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sattya.piseth.crudrestapis.shared.domain.AuditedEntity;
import sattya.piseth.crudrestapis.notification.domain.Notification;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_recipient", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "notification_id", "recipient_id"}),
    @UniqueConstraint(columnNames = {"tenant_id", "id"})
})
public class InboxItem extends AuditedEntity {
    @Column(nullable = false, updatable = false)
    private UUID notificationId;
    @Column(nullable = false, updatable = false)
    private UUID recipientId;
    private Instant readAt;
    private Instant archivedAt;

    public InboxItem(Notification notification, Recipient recipient) {
        super(Objects.requireNonNull(notification, "notification").getTenantId());
        requireSameTenant(recipient);
        this.notificationId = notification.getId();
        this.recipientId = recipient.getId();
    }

    public void markRead(Instant when) {
        Objects.requireNonNull(when, "when");
        if (readAt == null) {
            readAt = when;
        }
    }

    public void archive(Instant when) {
        Objects.requireNonNull(when, "when");
        if (archivedAt == null) {
            archivedAt = when;
        }
    }
}
