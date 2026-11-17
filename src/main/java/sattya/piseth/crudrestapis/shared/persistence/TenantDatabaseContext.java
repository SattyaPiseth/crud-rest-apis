package sattya.piseth.crudrestapis.shared.persistence;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.UUID;

/** Sets the authenticated application tenant for the lifetime of the current database transaction. */
@Component
@RequiredArgsConstructor
public class TenantDatabaseContext {
    private final EntityManager entityManager;

    public void setTenant(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (!entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException("Tenant database context requires an active transaction");
        }
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', :tenant, true)")
                .setParameter("tenant", tenantId.toString())
                .getSingleResult();
    }
}
