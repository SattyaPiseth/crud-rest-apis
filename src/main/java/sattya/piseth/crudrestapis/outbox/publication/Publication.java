package sattya.piseth.crudrestapis.outbox.publication;

import java.util.UUID;

/** Stable business envelope. Never include its payload in logs. */
public record Publication(UUID eventId, UUID tenantId, UUID aggregateId,
                          String eventType, int schemaVersion, String payload) {
    @Override public String toString() { return "Publication[redacted]"; }
}
