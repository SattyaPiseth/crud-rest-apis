package sattya.piseth.crudrestapis.outbox.publication;

import java.io.IOException;
import java.time.Duration;

/** No production adapter is provided in Phase 2. Implementations must honor interruption
 * and configure their own network timeout no greater than the supplied timeout.
 * ACKNOWLEDGED means explicit durable acceptance, never merely an attempted send.
 * Adapters classify known failures as PublicationOutcome; unclassified I/O failures
 * may propagate as IOException, and interruption must propagate as InterruptedException. */
@FunctionalInterface
public interface OutboxPublisher {
    PublicationOutcome publish(Publication event, Duration timeout) throws IOException, InterruptedException;
}
