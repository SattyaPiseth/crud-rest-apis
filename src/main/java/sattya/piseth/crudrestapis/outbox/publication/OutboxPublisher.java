package sattya.piseth.crudrestapis.outbox.publication;

import java.time.Duration;

/** No production adapter is provided in Phase 2. Implementations must honor interruption
 * and configure their own network timeout no greater than the supplied timeout.
 * ACKNOWLEDGED means explicit durable acceptance, never merely an attempted send. */
@FunctionalInterface
public interface OutboxPublisher {
    PublicationOutcome publish(Publication event, Duration timeout) throws Exception;
}
