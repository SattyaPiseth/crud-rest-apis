package sattya.piseth.crudrestapis.shared.audit;

import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.Optional;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

@Component
@Order(10)
public final class SecurityActorProvider implements ActorProvider {
    @Override
    public Optional<String> currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            var issuer = text(jwt.getToken().getClaimAsString("iss"), "issuer", 300);
            var subject = text(jwt.getToken().getSubject(), "subject", 180);
            // Length prefix makes the composite unambiguous.
            return Optional.of(text("jwt:" + issuer.length() + ":" + issuer + ":" + subject,
                    "actor", 512));
        }
        return Optional.of(text("principal:" + authentication.getName(), "actor", 512));
    }
}
