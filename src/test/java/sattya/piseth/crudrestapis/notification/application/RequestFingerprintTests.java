package sattya.piseth.crudrestapis.notification.application;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mockStatic;

class RequestFingerprintTests {
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
