package sattya.piseth.crudrestapis.notification.application;

import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned, length-prefixed encoding independent of JSON settings, locale, and display timezone. */
@Component
public final class RequestFingerprint {
    public String forCommand(IngestNotification command) {
        return hash(command, null);
    }

    public String forEvent(NotificationRequestedEvent event) {
        return hash(event.command(), event.idempotencyKey());
    }

    private String hash(IngestNotification command, String key) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeInt(1); // Canonical encoding version. Preserve compatibility for retained keys.
            writeText(output, key);
            writeText(output, command.type());
            writeText(output, command.category());
            writeText(output, command.title());
            writeText(output, command.body());
            writeText(output, command.expiresAt() == null ? null : command.expiresAt().toString());
            output.writeInt(command.recipientIds().size());
            for (var id : command.recipientIds()) {
                output.writeLong(id.getMostSignificantBits());
                output.writeLong(id.getLeastSignificantBits());
            }
            output.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot compute request fingerprint", exception);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
