package sattya.piseth.crudrestapis.notification;

import org.junit.jupiter.api.Test;
import sattya.piseth.crudrestapis.notification.application.IngestNotification;
import sattya.piseth.crudrestapis.notification.application.NotificationRequestedEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class NotificationCommandTests {
    @Test
    void recipientCountMustBeBetweenOneAndOneThousandBeforeDeduplication() {
        var id = UUID.randomUUID();
        var empty = List.<UUID>of();
        var oversized = Collections.nCopies(1001, id);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IngestNotification("TYPE", "ORDERS", "Title", "Body", null, empty));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IngestNotification("TYPE", "ORDERS", "Title", "Body", null, oversized));
        var atLimit = new IngestNotification("TYPE", "ORDERS", "Title", "Body", null,
                Collections.nCopies(1000, id));
        assertThat(atLimit.recipientIds()).containsExactly(id);
    }

    @Test
    void commandCanonicalizesRecipientsAndDefensivelyCopiesTheInput() {
        var first = new UUID(0, 1);
        var second = new UUID(0, 2);
        var recipients = new ArrayList<>(List.of(second, first, second));
        var expiry = Instant.parse("2026-11-17T10:00:00.123456789Z");
        var command = new IngestNotification("TYPE", "ORDERS", "Title", "Body", expiry, recipients);
        recipients.clear();
        assertThat(command.recipientIds()).containsExactly(first, second);
        assertThat(command.expiresAt()).isEqualTo(Instant.parse("2026-11-17T10:00:00.123456Z"));
        var canonicalIds = command.recipientIds();
        assertThatThrownBy(() -> canonicalIds.add(first)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void commandRejectsMissingRecipientsAndNullEntries() {
        var withNull = Collections.<UUID>singletonList(null);
        assertThatNullPointerException().isThrownBy(() ->
                new IngestNotification("TYPE", "ORDERS", "Title", "Body", null, null));
        assertThatNullPointerException().isThrownBy(() ->
                new IngestNotification("TYPE", "ORDERS", "Title", "Body", null, withNull));
    }

    @Test
    void eventAcceptsSupportedSchemaAndRejectsOtherVersions() {
        var eventId = UUID.randomUUID();
        var command = new IngestNotification("TYPE", "ORDERS", "Title", "Body", null,
                List.of(UUID.randomUUID()));
        var event = new NotificationRequestedEvent(eventId, 1, "key", command);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.command()).isSameAs(command);
        assertThat(event.idempotencyKey()).isEqualTo("key");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new NotificationRequestedEvent(eventId, 0, "key", command));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new NotificationRequestedEvent(eventId, 2, "key", command));
    }
}
