package sattya.piseth.crudrestapis.notification;

import org.junit.jupiter.api.Test;
import sattya.piseth.crudrestapis.delivery.domain.Channel;
import sattya.piseth.crudrestapis.delivery.domain.Delivery;
import sattya.piseth.crudrestapis.delivery.domain.DeliveryStatus;
import sattya.piseth.crudrestapis.inbox.domain.InboxItem;
import sattya.piseth.crudrestapis.notification.domain.Notification;
import sattya.piseth.crudrestapis.outbox.domain.OutboxEvent;
import sattya.piseth.crudrestapis.preference.domain.NotificationPreference;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class NotificationLifecycleTests {
    private final UUID tenant = UUID.randomUUID();
    private final UUID aggregate = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void preferenceCanBeEnabledAndDisabledWithoutChangingItsScope() {
        var recipient = new Recipient(tenant, "issuer", "alice");
        var preference = new NotificationPreference(recipient, "ORDERS", Channel.EMAIL, false);
        assertThat(preference.isEnabled()).isFalse();
        preference.enable();
        preference.enable();
        assertThat(preference.isEnabled()).isTrue();
        preference.disable();
        preference.disable();
        assertThat(preference.isEnabled()).isFalse();
        assertThat(preference.getTenantId()).isEqualTo(tenant);
        assertThat(preference.getRecipientId()).isEqualTo(recipient.getId());
        assertThat(preference.getCategory()).isEqualTo("ORDERS");
        assertThat(preference.getChannel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    void preferenceRejectsMissingScopeAndInvalidCategory() {
        var recipient = new Recipient(tenant, "issuer", "alice");
        var tooLong = "x".repeat(101);
        assertThatNullPointerException().isThrownBy(() ->
                new NotificationPreference(null, "ORDERS", Channel.EMAIL, true));
        assertThatNullPointerException().isThrownBy(() ->
                new NotificationPreference(recipient, "ORDERS", null, true));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new NotificationPreference(recipient, null, Channel.EMAIL, true));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new NotificationPreference(recipient, " ", Channel.EMAIL, true));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new NotificationPreference(recipient, tooLong, Channel.EMAIL, true));
        var maximumCategory = "x".repeat(100);
        var preference = new NotificationPreference(recipient, maximumCategory, Channel.EMAIL, true);
        assertThat(preference.getCategory()).isEqualTo(maximumCategory);
        assertThat(preference.isEnabled()).isTrue();
    }

    @Test
    void outboxPublicationKeepsTheFirstTimestampAndRejectsNull() {
        var event = new OutboxEvent(tenant, aggregate, "CREATED", 1, "{}");
        assertThat(event.getPublishedAt()).isNull();
        assertThatNullPointerException().isThrownBy(() -> event.markPublished(null));
        assertThat(event.getPublishedAt()).isNull();
        event.markPublished(now);
        event.markPublished(now.plusSeconds(60));
        assertThat(event.getPublishedAt()).isEqualTo(now);
        assertThatNullPointerException().isThrownBy(() -> event.markPublished(null));
        assertThat(event.getPublishedAt()).isEqualTo(now);
        assertThat(event.getTenantId()).isEqualTo(tenant);
        assertThat(event.getAggregateId()).isEqualTo(aggregate);
        assertThat(event.getEventType()).isEqualTo("CREATED");
        assertThat(event.getSchemaVersion()).isEqualTo(1);
        assertThat(event.getPayload()).isEqualTo("{}");
    }

    @Test
    void outboxRejectsNonpositiveVersionsAndInvalidPayloads() {
        var oversizedPayload = "x".repeat(20001);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboxEvent(tenant, aggregate, "CREATED", 0, "{}"))
                .withMessage("schemaVersion must be positive");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboxEvent(tenant, aggregate, "CREATED", -1, "{}"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboxEvent(tenant, aggregate, "CREATED", 1, oversizedPayload));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboxEvent(tenant, aggregate, "CREATED", 1, " "));
        assertThatNullPointerException().isThrownBy(() ->
                new OutboxEvent(tenant, null, "CREATED", 1, "{}"));
    }

    @Test
    void permanentFailureRequiresAnActiveAttemptAndCannotBeRetried() {
        var recipient = new Recipient(tenant, "issuer", "alice");
        var notification = new Notification(tenant, "TYPE", "ORDERS", "Title", "Body", null);
        var item = new InboxItem(notification, recipient);
        var delivery = new Delivery(item, Channel.EMAIL, "endpoint:123");
        assertThatIllegalStateException().isThrownBy(delivery::failPermanently);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        delivery.startAttempt(now);
        delivery.failPermanently();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED_FINAL);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThatIllegalStateException().isThrownBy(() -> delivery.startAttempt(now));
        assertThatIllegalStateException().isThrownBy(delivery::accept);
        assertThatIllegalStateException().isThrownBy(() -> delivery.retryAt(now));
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED_FINAL);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
    }
}
