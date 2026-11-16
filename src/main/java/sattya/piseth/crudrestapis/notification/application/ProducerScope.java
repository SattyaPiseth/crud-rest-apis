package sattya.piseth.crudrestapis.notification.application;

import java.util.Objects;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

/** Trusted adapter context, derived from authorization; never copied from an untrusted payload. */
public record ProducerScope(UUID tenantId, String producerId) {
    public ProducerScope {
        Objects.requireNonNull(tenantId, "tenantId");
        producerId = text(producerId, "producerId", 100);
    }
}
