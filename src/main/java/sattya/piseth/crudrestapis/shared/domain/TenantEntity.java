package sattya.piseth.crudrestapis.shared.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Objects;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@MappedSuperclass
public abstract class TenantEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Version
    private Long version;

    protected TenantEntity(UUID tenantId) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    }

    public final void requireSameTenant(TenantEntity other) {
        if (!tenantId.equals(Objects.requireNonNull(other, "related entity").getTenantId())) {
            throw new IllegalArgumentException("Related entities must belong to the same tenant");
        }
    }
}
