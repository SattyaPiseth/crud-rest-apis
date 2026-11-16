package sattya.piseth.crudrestapis.notification.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import sattya.piseth.crudrestapis.shared.audit.BackgroundActor;

/** Transport-independent consumer; broker adapters call this before acknowledging a record. */
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {
    static final String CONSUMER_NAME = "notification-ingestion-v1";
    private final NotificationIngestionService ingestion;
    private final BackgroundActor backgroundActor;

    public IngestionResult consume(ProducerScope trustedSource, NotificationRequestedEvent event) {
        // Actor scope encloses the proxied service's commit/flush, not only its method body.
        return backgroundActor.callAs(CONSUMER_NAME, () -> ingestion.consume(trustedSource, event));
    }
}
