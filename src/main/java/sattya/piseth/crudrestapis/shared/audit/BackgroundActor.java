package sattya.piseth.crudrestapis.shared.audit;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.function.Supplier;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

/** Explicit, lexically scoped identity for trusted worker code; never bind request input. */
@Component
@Order(0)
public final class BackgroundActor implements ActorProvider {
    private static final ScopedValue<String> ACTOR = ScopedValue.newInstance();

    @Override
    public Optional<String> currentActor() {
        return ACTOR.isBound() ? Optional.of(ACTOR.get()) : Optional.empty();
    }

    public <T> T callAs(String serviceName, Supplier<T> work) {
        var actor = "service:" + text(serviceName, "serviceName", 120);
        return ScopedValue.where(ACTOR, actor).call(work::get);
    }
}
