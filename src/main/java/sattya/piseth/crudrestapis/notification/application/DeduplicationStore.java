package sattya.piseth.crudrestapis.notification.application;

import java.util.UUID;

/** Reservations participate in the caller's database transaction; they are never committed alone. */
public interface DeduplicationStore {
    Reservation reserveRequest(ProducerScope source, String key, String fingerprint, UUID candidateId);
    Reservation reserveEvent(ProducerScope source, String consumer, UUID eventId,
                             String fingerprint, UUID candidateId);
    void completeEvent(ProducerScope source, String consumer, UUID eventId, UUID notificationId);

    record Reservation(UUID notificationId, boolean acquired) {}
}
