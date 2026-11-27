package sattya.piseth.crudrestapis.outbox.publication;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

public record OutboxSettings(List<UUID> tenants, int concurrency, int batchSize, int maxAttempts,
                             Duration pollInterval, Duration publicationTimeout, Duration leaseDuration,
                             Duration finalizationMargin, Duration initialRetry, Duration maxRetry,
                             Duration shutdownTimeout) {
    public OutboxSettings {
        if (tenants == null || tenants.isEmpty() || tenants.size() > 1000 || tenants.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Outbox requires 1..1000 authorized tenants");
        tenants = List.copyOf(new java.util.LinkedHashSet<>(tenants));
        if (concurrency < 1 || concurrency > 32 || batchSize < 1 || batchSize > concurrency
                || maxAttempts < 1 || maxAttempts > 1000)
            throw new IllegalArgumentException("Invalid outbox capacity or attempt limit");
        for (var duration : new Duration[]{pollInterval, publicationTimeout, leaseDuration,
                finalizationMargin, initialRetry, maxRetry, shutdownTimeout}) {
            if (duration == null || duration.toMillis() < 1 || duration.compareTo(Duration.ofDays(1)) > 0)
                throw new IllegalArgumentException("Outbox durations must be between 1ms and 1 day");
        }
        if (finalizationMargin.compareTo(Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(publicationTimeout.plus(finalizationMargin)) <= 0
                || initialRetry.compareTo(maxRetry) > 0)
            throw new IllegalArgumentException("Invalid outbox lease margin or retry bounds");
    }

    public static OutboxSettings from(Environment environment) {
        var binder = Binder.get(environment);
        try {
            return new OutboxSettings(binder.bind("app.outbox.tenants",
                    org.springframework.boot.context.properties.bind.Bindable.listOf(UUID.class)).orElse(List.of()),
                    integer(binder, "concurrency", 4), integer(binder, "batch-size", 1),
                    integer(binder, "max-attempts", 5), duration(binder, "poll-interval", "1s"),
                    duration(binder, "publication-timeout", "10s"), duration(binder, "lease-duration", "30s"),
                    duration(binder, "finalization-margin", "5s"), duration(binder, "initial-retry", "1s"),
                    duration(binder, "max-retry", "5m"), duration(binder, "shutdown-timeout", "15s"));
        } catch (RuntimeException _) {
            throw new IllegalArgumentException("Invalid app.outbox configuration; check tenant list and bounded settings");
        }
    }
    private static int integer(Binder binder, String name, int fallback) {
        return binder.bind("app.outbox." + name, Integer.class).orElse(fallback);
    }
    private static Duration duration(Binder binder, String name, String fallback) {
        return binder.bind("app.outbox." + name, Duration.class)
                .orElse(org.springframework.boot.convert.DurationStyle.detectAndParse(fallback));
    }
}
