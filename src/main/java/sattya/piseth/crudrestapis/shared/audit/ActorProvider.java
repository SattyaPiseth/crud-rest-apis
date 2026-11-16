package sattya.piseth.crudrestapis.shared.audit;

import java.util.Optional;

/** Resolves an actor from one trusted execution context. */
public interface ActorProvider {
    Optional<String> currentActor();
}
