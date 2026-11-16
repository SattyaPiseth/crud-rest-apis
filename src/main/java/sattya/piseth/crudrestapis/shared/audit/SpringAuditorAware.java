package sattya.piseth.crudrestapis.shared.audit;

import org.springframework.data.domain.AuditorAware;
import java.util.List;
import java.util.Optional;

/** Polymorphic providers support HTTP requests and explicitly identified background work. */
public final class SpringAuditorAware implements AuditorAware<String> {
    private final List<ActorProvider> providers;

    public SpringAuditorAware(List<ActorProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public Optional<String> getCurrentAuditor() {
        return Optional.of(providers.stream()
                .map(ActorProvider::currentActor)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "An authenticated or explicit background actor is required for audited writes")));
    }
}
