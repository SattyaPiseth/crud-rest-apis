package sattya.piseth.crudrestapis.notification.application;

import java.util.Objects;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

public record NotificationRequestedEvent(UUID eventId, int schemaVersion,
                                         String idempotencyKey, IngestNotification command) {
    public NotificationRequestedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(command, "command");
        idempotencyKey = text(idempotencyKey, "idempotencyKey", 200);
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported notification event schema version");
        }
    }
}
