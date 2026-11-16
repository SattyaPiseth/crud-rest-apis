package sattya.piseth.crudrestapis.preference.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sattya.piseth.crudrestapis.shared.domain.AuditedEntity;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import sattya.piseth.crudrestapis.delivery.domain.Channel;
import java.util.Objects;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_preference", uniqueConstraints =
    @UniqueConstraint(columnNames = {"tenant_id", "recipient_id", "category", "channel"}))
public class NotificationPreference extends AuditedEntity {
    @Column(nullable = false, updatable = false)
    private UUID recipientId;
    @Column(nullable = false, updatable = false, length = 100)
    private String category;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Channel channel;
    @Column(nullable = false)
    private boolean enabled;

    public NotificationPreference(Recipient recipient, String category, Channel channel, boolean enabled) {
        super(Objects.requireNonNull(recipient, "recipient").getTenantId());
        recipientId = recipient.getId();
        this.category = text(category, "category", 100);
        this.channel = Objects.requireNonNull(channel, "channel");
        this.enabled = enabled;
    }

    public void enable() { enabled = true; }
    public void disable() { enabled = false; }
}
