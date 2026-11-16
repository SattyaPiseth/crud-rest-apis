package sattya.piseth.crudrestapis.notification.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

/** Immutable command for bounded, in-app fan-out. Recipients form an unordered set. */
public record IngestNotification(String type, String category, String title, String body,
                                 Instant expiresAt, List<UUID> recipientIds) {
    public IngestNotification {
        type = text(type, "type", 100);
        category = text(category, "category", 100);
        title = text(title, "title", 200);
        body = text(body, "body", 10000);
        Objects.requireNonNull(recipientIds, "recipientIds");
        if (recipientIds.isEmpty() || recipientIds.size() > 1000) {
            throw new IllegalArgumentException("Supply between 1 and 1000 recipient IDs");
        }
        recipientIds = recipientIds.stream()
                .map(id -> Objects.requireNonNull(id, "recipientId"))
                .distinct().sorted().toList();
        if (expiresAt != null) {
            expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        }
    }
}
