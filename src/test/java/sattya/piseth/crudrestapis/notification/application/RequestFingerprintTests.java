package sattya.piseth.crudrestapis.notification.application;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mockStatic;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;

class RequestFingerprintTests {
    private final RequestFingerprint fingerprints = new RequestFingerprint();
    private static final UUID RECIPIENT = new UUID(0, 1);

    @Test
    void versionOneHashesRemainCompatibleWithRetainedReceipts() {
        // Fixed v1 fixtures, independently encoded with big-endian lengths and UTF-8.
        // Never regenerate these merely to make a changed encoder pass.
        var command = new IngestNotification("TYPE", "CATEGORY", "Title", "Body", null, List.of(RECIPIENT));
        assertThat(fingerprints.forCommand(command))
                .isEqualTo("7acebeecc9fd44d9cac8f89f120abfc8e181bd42df3d618b35b81a497a3c62e8");
        assertThat(fingerprints.forEvent(new NotificationRequestedEvent(new UUID(0, 2), 1, "key", command)))
                .isEqualTo("e546985e499c81cca20c99ff33a8b75c4374ec0d6c9e5579ce03046e4e4a065e");
    }

    @Test
    void everySemanticFieldAndEventKeyAffectsFingerprint() {
        var original = new IngestNotification("TYPE", "CATEGORY", "Title", "Body", null, List.of(RECIPIENT));
        var variants = List.of(
                new IngestNotification("OTHER", "CATEGORY", "Title", "Body", null, List.of(RECIPIENT)),
                new IngestNotification("TYPE", "OTHER", "Title", "Body", null, List.of(RECIPIENT)),
                new IngestNotification("TYPE", "CATEGORY", "Other", "Body", null, List.of(RECIPIENT)),
                new IngestNotification("TYPE", "CATEGORY", "Title", "Body ", null, List.of(RECIPIENT)),
                new IngestNotification("TYPE", "CATEGORY", "Title", "Body", Instant.EPOCH, List.of(RECIPIENT)),
                new IngestNotification("TYPE", "CATEGORY", "Title", "Body", null, List.of(new UUID(0, 2))));
        for (var variant : variants) {
            assertThat(fingerprints.forCommand(variant)).isNotEqualTo(fingerprints.forCommand(original));
        }
        var event = new NotificationRequestedEvent(new UUID(0, 3), 1, "key", original);
        assertThat(fingerprints.forEvent(new NotificationRequestedEvent(event.eventId(), 1, "other", original)))
                .isNotEqualTo(fingerprints.forEvent(event));
        assertThat(fingerprints.forEvent(new NotificationRequestedEvent(new UUID(0, 4), 1, "key", original)))
                .isEqualTo(fingerprints.forEvent(event));
    }

    @Test
    void recipientOrderDuplicatesAndSubmicrosecondExpiryDoNotChangeFingerprint() {
        var second = new UUID(0, 2);
        var first = new IngestNotification("TYPE", "CATEGORY", "Title", "Body",
                Instant.parse("2030-01-01T00:00:00.123456Z"), List.of(RECIPIENT, second));
        var equivalent = new IngestNotification("TYPE", "CATEGORY", "Title", "Body",
                Instant.parse("2030-01-01T00:00:00.123456999Z"), List.of(second, RECIPIENT, second));
        assertThat(fingerprints.forCommand(equivalent)).isEqualTo(fingerprints.forCommand(first));
    }

    @Test
    void wrapsDigestProviderFailures() {
        var command = new IngestNotification("TYPE", "CATEGORY", "Title", "Body", null,
                List.of(UUID.randomUUID()));
        try (var messageDigest = mockStatic(MessageDigest.class)) {
            messageDigest.when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("SHA-256 unavailable"));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new RequestFingerprint().forCommand(command))
                    .withMessage("Cannot compute request fingerprint")
                    .withCauseInstanceOf(NoSuchAlgorithmException.class);
        }
    }
}
