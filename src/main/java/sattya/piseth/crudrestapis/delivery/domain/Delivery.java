package sattya.piseth.crudrestapis.delivery.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sattya.piseth.crudrestapis.shared.domain.AuditedEntity;
import sattya.piseth.crudrestapis.inbox.domain.InboxItem;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "delivery", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "inbox_item_id", "channel", "destination_key"}),
    @UniqueConstraint(columnNames = {"tenant_id", "id"})
})
public class Delivery extends AuditedEntity {
    @Column(nullable = false, updatable = false)
    private UUID inboxItemId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Channel channel;
    /** Opaque destination reference, never a raw email address or device token. */
    @Column(nullable = false, updatable = false, length = 200)
    private String destinationKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeliveryStatus status = DeliveryStatus.PENDING;
    @Column(nullable = false)
    private int attemptCount;
    private Instant nextAttemptAt;

    public Delivery(InboxItem item, Channel channel, String destinationKey) {
        super(Objects.requireNonNull(item, "item").getTenantId());
        inboxItemId = item.getId();
        this.channel = Objects.requireNonNull(channel, "channel");
        this.destinationKey = text(destinationKey, "destinationKey", 200);
    }

    public void startAttempt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != DeliveryStatus.PENDING && status != DeliveryStatus.RETRY_WAIT) {
            throw new IllegalStateException("Delivery is not awaiting an attempt");
        }
        if (nextAttemptAt != null && now.isBefore(nextAttemptAt)) {
            throw new IllegalStateException("Retry is not due");
        }
        status = DeliveryStatus.IN_PROGRESS;
        attemptCount++;
        nextAttemptAt = null;
    }

    public void accept() {
        requireInProgress();
        status = DeliveryStatus.ACCEPTED;
    }

    public void retryAt(Instant when) {
        requireInProgress();
        nextAttemptAt = Objects.requireNonNull(when, "when");
        status = DeliveryStatus.RETRY_WAIT;
    }

    public void failPermanently() {
        requireInProgress();
        status = DeliveryStatus.FAILED_FINAL;
    }

    private void requireInProgress() {
        if (status != DeliveryStatus.IN_PROGRESS) {
            throw new IllegalStateException("Delivery has no active attempt");
        }
    }
}
