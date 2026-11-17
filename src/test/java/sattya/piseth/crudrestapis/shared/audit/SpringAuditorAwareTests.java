package sattya.piseth.crudrestapis.shared.audit;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SpringAuditorAwareTests {
    private final BackgroundActor background = new BackgroundActor();
    private final SpringAuditorAware auditor =
            new SpringAuditorAware(List.of(background, new SecurityActorProvider()));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingAndAnonymousActors() {
        assertThatIllegalStateException().isThrownBy(auditor::getCurrentAuditor);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatIllegalStateException().isThrownBy(auditor::getCurrentAuditor);
    }

    @Test
    void rejectsUnauthenticatedPrincipal() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated("alice", "unused"));
        SecurityContextHolder.setContext(context);
        assertThatIllegalStateException().isThrownBy(auditor::getCurrentAuditor);
    }

    @Test
    void authenticatedNonJwtPrincipalHasAnExplicitIdentityPrefix() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken("alice", "unused", "ROLE_USER"));
        SecurityContextHolder.setContext(context);
        assertThat(auditor.getCurrentAuditor()).contains("principal:alice");
    }

    @Test
    void includesIssuerInJwtActorIdentity() {
        var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .issuer("https://identity.example").subject("alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        assertThat(auditor.getCurrentAuditor())
                .contains("jwt:24:https://identity.example:alice");
    }

    @Test
    void nestedBackgroundScopeRestoresActorEvenAfterFailure() {
        background.callAs("outer", () -> {
            assertThat(auditor.getCurrentAuditor()).contains("service:outer");
            assertThatThrownBy(() -> background.callAs("inner", () -> {
                assertThat(auditor.getCurrentAuditor()).contains("service:inner");
                throw new IllegalArgumentException("failure");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(auditor.getCurrentAuditor()).contains("service:outer");
            return null;
        });
        assertThatIllegalStateException().isThrownBy(auditor::getCurrentAuditor);
    }
}
