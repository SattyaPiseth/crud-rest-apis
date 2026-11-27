package sattya.piseth.crudrestapis.outbox.publication;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Equal jitter: [half of capped exponential delay, capped exponential delay]. */
public final class RetryBackoff {
    private final long initial;
    private final long maximum;
    private final DoubleSupplier random;
    public RetryBackoff(OutboxSettings settings, DoubleSupplier random) {
        initial = settings.initialRetry().toMillis();
        maximum = settings.maxRetry().toMillis();
        this.random = random;
    }
    public Duration delay(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("Attempt must be positive");
        long cap = initial;
        for (int i = 1; i < attempt && cap < maximum; i++) cap = Math.min(maximum, cap * 2);
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample >= 1)
            throw new IllegalArgumentException("Jitter sample must be in [0,1)");
        long lower = Math.max(1, cap / 2);
        return Duration.ofMillis(lower + (long) (sample * (cap - lower + 1)));
    }
}
