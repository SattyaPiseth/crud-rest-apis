package sattya.piseth.crudrestapis.notification.application;

import java.util.UUID;

/** replayed means the notification already existed, not that a new delivery was attempted. */
public record IngestionResult(UUID notificationId, boolean replayed) {}
