package sattya.piseth.crudrestapis.notification;

import org.junit.jupiter.api.Test;
import sattya.piseth.crudrestapis.notification.domain.Notification;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import sattya.piseth.crudrestapis.inbox.domain.InboxItem;
import sattya.piseth.crudrestapis.delivery.domain.*;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NotificationDomainTests {
    private final UUID tenant = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-16T10:00:00Z");

    private Notification notification() {
        return new Notification(tenant, "ORDER_SHIPPED", "ORDERS", "Shipped", "Your order shipped.", now);
    }

    @Test
    void refusesCrossTenantRecipient() {
        var other = new Recipient(UUID.randomUUID(), "issuer", "subject");
        assertThatIllegalArgumentException().isThrownBy(() -> new InboxItem(notification(), other));
    }

    @Test
    void readAndArchiveAreIdempotent() {
        var item = new InboxItem(notification(), new Recipient(tenant, "issuer", "subject"));
        item.markRead(now);
        item.markRead(now.plusSeconds(60));
        item.archive(now);
        item.archive(now.plusSeconds(60));
        assertThat(item.getReadAt()).isEqualTo(now);
        assertThat(item.getArchivedAt()).isEqualTo(now);
    }

    @Test
    void deliveryEnforcesTransitionsAndRetrySchedule() {
        var item = new InboxItem(notification(), new Recipient(tenant, "issuer", "subject"));
        var delivery = new Delivery(item, Channel.EMAIL, "endpoint:123");
        assertThatIllegalStateException().isThrownBy(delivery::accept);
        delivery.startAttempt(now);
        delivery.retryAt(now.plusSeconds(30));
        assertThatIllegalStateException().isThrownBy(() -> delivery.startAttempt(now));
        delivery.startAttempt(now.plusSeconds(30));
        delivery.accept();
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.ACCEPTED);
        assertThatIllegalStateException().isThrownBy(() -> delivery.startAttempt(now.plusSeconds(60)));
    }

    @Test
    void expiryIncludesExactBoundaryAndBlankContentIsRejected() {
        assertThat(notification().isExpiredAt(now)).isTrue();
        assertThat(notification().isExpiredAt(now.minusSeconds(1))).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Notification(tenant, "TYPE", "CATEGORY", " ", "body", null));
    }
}
