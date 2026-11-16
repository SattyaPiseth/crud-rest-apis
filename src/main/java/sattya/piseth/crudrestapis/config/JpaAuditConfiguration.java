package sattya.piseth.crudrestapis.config;

import org.springframework.context.annotation.*;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import sattya.piseth.crudrestapis.shared.audit.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditTimeProvider")
public class JpaAuditConfiguration {
    @Bean
    AuditorAware<String> auditorAware(List<ActorProvider> providers) {
        return new SpringAuditorAware(providers);
    }

    @Bean
    DateTimeProvider auditTimeProvider(Clock clock) {
        // PostgreSQL timestamps retain microseconds, so values survive a round-trip unchanged.
        return () -> Optional.of(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }
}
