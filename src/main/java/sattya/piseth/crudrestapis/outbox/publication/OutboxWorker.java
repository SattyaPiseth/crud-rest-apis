package sattya.piseth.crudrestapis.outbox.publication;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded transport capacity is retained even when an adapter ignores interruption. */
public final class OutboxWorker implements SmartLifecycle, AutoCloseable {
    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final OutboxSettings settings;
    private final MeterRegistry metrics;
    private final RetryBackoff backoff;
    private final Semaphore capacity;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService transports;
    private final Set<Thread> controllers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, OutboxStore.Backlog> snapshots = new ConcurrentHashMap<>();
    private final Runnable closeResources;
    private final java.util.concurrent.locks.ReentrantLock polling = new java.util.concurrent.locks.ReentrantLock();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private volatile boolean running;
    private boolean closed;
    private int tenantIndex;

    public OutboxWorker(OutboxStore store, OutboxPublisher publisher, OutboxSettings settings,
                        MeterRegistry metrics, Runnable closeResources) {
        this(store, publisher, settings, metrics, closeResources, new Semaphore(settings.concurrency()));
    }

    OutboxWorker(OutboxStore store, OutboxPublisher publisher, OutboxSettings settings,
                 MeterRegistry metrics, Runnable closeResources, Semaphore capacity) {
        this.store = store;
        this.publisher = publisher;
        this.settings = settings;
        this.metrics = metrics;
        this.closeResources = closeResources;
        // Non-security randomness: only spreads retries within the configured backoff bounds.
        // It never generates claim tokens or influences tenant authorization or lease ownership.
        backoff = new RetryBackoff(settings, () -> ThreadLocalRandom.current().nextDouble());
        this.capacity = capacity;
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("outbox-poll").factory());
        transports = Executors.newFixedThreadPool(settings.concurrency(),
                Thread.ofPlatform().daemon().name("outbox-transport-", 0).factory());
        for (var state : List.of("PENDING", "PROCESSING", "RETRY", "PUBLISHED", "FAILED")) {
            io.micrometer.core.instrument.Gauge.builder("outbox.events", snapshots,
                    values -> values.values().stream().mapToLong(s -> s.states().getOrDefault(state, 0L)).sum())
                    .tag("state", state).register(metrics);
        }
        io.micrometer.core.instrument.Gauge.builder("outbox.expired.leases", snapshots,
                values -> values.values().stream().mapToLong(OutboxStore.Backlog::expiredLeases).sum()).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("outbox.oldest.unpublished.seconds", snapshots,
                values -> values.values().stream().mapToDouble(OutboxStore.Backlog::oldestSeconds).max().orElse(0)).register(metrics);
    }

    @Override public synchronized void start() {
        if (running) return;
        if (closed) throw new IllegalStateException("Outbox worker cannot restart after shutdown");
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollOnce, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Database I/O never holds the lifecycle monitor needed by stop(). */
    void pollOnce() {
        if (!running || !polling.tryLock()) return;
        try { pollTenant(); }
        finally { polling.unlock(); }
    }

    private void pollTenant() {
        if (!running) return;
        var tenant = settings.tenants().get(tenantIndex);
        int slots = Math.min(settings.batchSize(), capacity.availablePermits());
        if (slots == 0 || !capacity.tryAcquire(slots)) return;
        tenantIndex = (tenantIndex + 1) % settings.tenants().size();
        int handedOff = 0;
        try {
            var claims = store.claim(tenant, slots);
            synchronized (this) {
                // stop() uses this same short gate: no controllers can be added after it.
                if (!running) return;
                for (var claim : claims) {
                    var controller = Thread.ofVirtual().unstarted(() -> runAttempt(claim));
                    controllers.add(controller);
                    controller.start();
                    handedOff++;
                }
            }
            if (running) snapshots.put(tenant, store.backlog(tenant));
        } catch (RuntimeException _) {
            if (running) {
                metrics.counter("outbox.errors", "operation", "poll").increment();
                LoggerFactory.getLogger(OutboxWorker.class).warn("Outbox polling failed; work remains recoverable");
            }
        } finally {
            capacity.release(slots - handedOff);
        }
    }

    private void runAttempt(OutboxStore.Claim claim) {
        // Future completion/cancellation does not imply the adapter has exited.
        // Release only after BOTH controller and the actual run() have returned.
        var remaining = new AtomicInteger(2);
        Runnable finished = () -> { if (remaining.decrementAndGet() == 0) capacity.release(); };
        var task = new FutureTask<PublicationOutcome>(() -> publisher.publish(claim.event(), settings.publicationTimeout())) {
            @Override public void run() {
                try { super.run(); }
                finally { finished.run(); }
            }
        };
        var sample = Timer.start(metrics);
        try {
            transports.execute(task);
        } catch (RejectedExecutionException _) {
            task.cancel(false);
            // It was never submitted, so no executor will invoke its run/finally.
            finished.run();
        }
        try {
            var outcome = awaitOutcome(task);
            if (outcome == null) outcome = PublicationOutcome.UNKNOWN;
            metrics.counter("outbox.attempts", "outcome", outcome.name()).increment();
            boolean finalized = store.finish(claim, outcome, backoff.delay(claim.attempt()));
            metrics.counter("outbox.finalizations", "result", finalized ? "APPLIED" : "FENCED").increment();
        } catch (InterruptedException _) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            // No success recorded on shutdown interruption; lease recovery is authoritative.
        } catch (RuntimeException _) {
            metrics.counter("outbox.errors", "operation", "finalize").increment();
            LoggerFactory.getLogger(OutboxWorker.class).warn("Outbox finalization failed; lease recovery will retry");
        } finally {
            sample.stop(metrics.timer("outbox.processing"));
            controllers.remove(Thread.currentThread());
            finished.run();
        }
    }

    private PublicationOutcome awaitOutcome(FutureTask<PublicationOutcome> task) throws InterruptedException {
        try {
            return task.get(settings.publicationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            task.cancel(true);
            return PublicationOutcome.UNKNOWN;
        } catch (ExecutionException | CancellationException _) {
            return PublicationOutcome.UNKNOWN;
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop() {
        long deadline = System.nanoTime() + settings.shutdownTimeout().toNanos();
        List<Thread> active;
        synchronized (this) {
            running = false;
            closed = true;
            scheduler.shutdownNow();
            active = List.copyOf(controllers);
        }
        // Never wait for the poller's JDBC I/O. An in-flight claim may commit, but
        // dispatch rechecks running and leaves that claim for lease recovery.
        for (var iterator = active.iterator(); iterator.hasNext() && !Thread.currentThread().isInterrupted();) {
            var thread = iterator.next();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { thread.join(java.time.Duration.ofNanos(remaining)); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
        active.forEach(Thread::interrupt);
        for (var pending : transports.shutdownNow()) {
            if (pending instanceof FutureTask<?> task) {
                task.cancel(false);
                // A cancelled queued task cannot publish; invoke run to release its capacity.
                task.run();
            }
        }
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public void close() {
        stop();
        if (resourcesClosed.compareAndSet(false, true)) closeResources.run();
    }
}
