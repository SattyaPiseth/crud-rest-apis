package sattya.piseth.crudrestapis.recipient.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sattya.piseth.crudrestapis.shared.domain.AuditedEntity;
import java.util.UUID;
import static sattya.piseth.crudrestapis.shared.domain.DomainChecks.text;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "recipient", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "issuer", "subject"}),
    @UniqueConstraint(columnNames = {"tenant_id", "id"})
})
public class Recipient extends AuditedEntity {
    @Column(nullable = false, updatable = false, length = 300)
    private String issuer;
    @Column(nullable = false, updatable = false, length = 180)
    private String subject;
    @Column(nullable = false)
    private boolean active = true;

    public Recipient(UUID tenantId, String issuer, String subject) {
        super(tenantId);
        this.issuer = text(issuer, "issuer", 300);
        this.subject = text(subject, "subject", 180);
    }

    public void deactivate() {
        active = false;
    }
}
