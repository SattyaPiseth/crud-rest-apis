package sattya.piseth.crudrestapis.inbox.application;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import sattya.piseth.crudrestapis.inbox.domain.InboxItem;
import sattya.piseth.crudrestapis.notification.domain.Notification;
import sattya.piseth.crudrestapis.shared.persistence.TenantDatabaseContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InboxService {
    private static final String TENANT_PARAMETER = "tenant";

    private final EntityManager entityManager;
    private final Clock clock;
    private final TenantDatabaseContext tenantContext;

    public record Owner(UUID tenant, String issuer, String subject) {}
    public record Item(UUID id, UUID notificationId, String type, String category, String title, String body,
                       Instant createdAt, Instant expiresAt, Instant readAt, Instant archivedAt, long version) {}
    public record Mutation(UUID id, Instant readAt, Instant archivedAt, long version) {}
    public record Page(List<Item> items, int page, int size, boolean hasNext) {}

    private UUID recipient(Owner owner) {
        return entityManager.createQuery("SELECT r.id FROM Recipient r WHERE r.tenantId = :tenant AND r.issuer = :issuer AND r.subject = :subject AND r.active = true", UUID.class)
                .setParameter(TENANT_PARAMETER, owner.tenant()).setParameter("issuer", owner.issuer())
                .setParameter("subject", owner.subject()).getResultList().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No active recipient membership"));
    }

    @Transactional(readOnly = true)
    public Page list(Owner owner, int page, int size) {
        tenantContext.setTenant(owner.tenant());
        var recipient = recipient(owner);
        var rows = entityManager.createQuery("SELECT i, n FROM InboxItem i, Notification n WHERE i.notificationId = n.id AND i.tenantId = n.tenantId AND i.tenantId = :tenant AND i.recipientId = :recipient AND i.archivedAt IS NULL ORDER BY i.createdAt DESC, i.id DESC", Object[].class)
                .setParameter(TENANT_PARAMETER, owner.tenant()).setParameter("recipient", recipient)
                .setFirstResult(page * size).setMaxResults(size + 1).getResultList();
        return new Page(rows.stream().limit(size).map(row -> view((InboxItem) row[0], (Notification) row[1])).toList(), page, size, rows.size() > size);
    }

    @Transactional(readOnly = true)
    public Item get(Owner owner, UUID id) {
        tenantContext.setTenant(owner.tenant());
        return view(load(owner, id));
    }

    @Transactional
    public Mutation update(Owner owner, UUID id, long version, boolean archive) {
        tenantContext.setTenant(owner.tenant());
        var item = load(owner, id);
        if (item.getVersion() != version) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stale inbox version");
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (archive) item.archive(now); else item.markRead(now);
        entityManager.flush();
        return new Mutation(item.getId(), item.getReadAt(), item.getArchivedAt(), item.getVersion());
    }

    private InboxItem load(Owner owner, UUID id) {
        var recipient = recipient(owner);
        return entityManager.createQuery("SELECT i FROM InboxItem i WHERE i.id = :id AND i.tenantId = :tenant AND i.recipientId = :recipient", InboxItem.class)
                .setParameter("id", id).setParameter(TENANT_PARAMETER, owner.tenant()).setParameter("recipient", recipient)
                .getResultList().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inbox item not found"));
    }

    private Item view(InboxItem item) {
        var notification = entityManager.createQuery("SELECT n FROM Notification n WHERE n.id = :id AND n.tenantId = :tenant", Notification.class)
                .setParameter("id", item.getNotificationId()).setParameter(TENANT_PARAMETER, item.getTenantId()).getSingleResult();
        return view(item, notification);
    }

    private Item view(InboxItem item, Notification notification) {
        return new Item(item.getId(), notification.getId(), notification.getType(), notification.getCategory(),
                notification.getTitle(), notification.getBody(), item.getCreatedAt(), notification.getExpiresAt(),
                item.getReadAt(), item.getArchivedAt(), item.getVersion());
    }
}
