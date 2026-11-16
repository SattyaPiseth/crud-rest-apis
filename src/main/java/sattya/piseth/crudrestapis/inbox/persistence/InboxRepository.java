package sattya.piseth.crudrestapis.inbox.persistence;

import org.springframework.data.repository.Repository;
import sattya.piseth.crudrestapis.inbox.domain.InboxItem;
import java.util.Optional;
import java.util.UUID;

/** Every read requires both tenant and recipient ownership. */
public interface InboxRepository extends Repository<InboxItem, UUID> {
    InboxItem save(InboxItem item);
    Optional<InboxItem> findByIdAndTenantIdAndRecipientId(UUID id, UUID tenantId, UUID recipientId);
}
