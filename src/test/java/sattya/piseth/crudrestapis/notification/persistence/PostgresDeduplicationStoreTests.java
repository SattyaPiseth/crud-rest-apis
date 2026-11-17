package sattya.piseth.crudrestapis.notification.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import sattya.piseth.crudrestapis.notification.application.ProducerScope;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresDeduplicationStoreTests {
    @Test
    void missingAuditActorPreventsReservationInsert() {
        var entityManager = mock(EntityManager.class);
        var query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);

        AuditorAware<String> auditorAware = Optional::empty;
        var store = new PostgresDeduplicationStore(entityManager, auditorAware, Clock.systemUTC());

        assertThatIllegalStateException().isThrownBy(() -> store.reserveRequest(
                        new ProducerScope(UUID.randomUUID(), "producer"), "key", "fingerprint", UUID.randomUUID()))
                .withMessage("An audit actor is required");
    }
}
