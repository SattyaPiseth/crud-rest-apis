package sattya.piseth.crudrestapis.outbox.publication;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class OutboxWorkerTests {
    private final UUID tenant=UUID.randomUUID();
    private final OutboxSettings settings=new OutboxSettings(List.of(tenant),1,1,3,
            Duration.ofMillis(10),Duration.ofMillis(50),Duration.ofSeconds(6),Duration.ofSeconds(5),
            Duration.ofMillis(10),Duration.ofSeconds(1),Duration.ofMillis(50));
    private final OutboxStore store=mock(OutboxStore.class);
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    private final OutboxStore.Claim claim=new OutboxStore.Claim(
            new Publication(UUID.randomUUID(),tenant,UUID.randomUUID(),"event",1,"{}"),UUID.randomUUID(),1);

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while(latch.getCount()>0) {
            try { latch.await(); } catch(InterruptedException _) { /* Simulates stuck I/O. */ }
        }
    }
    private OutboxWorker worker(OutboxPublisher publisher) {
        when(store.backlog(tenant)).thenReturn(new OutboxStore.Backlog(Map.of(),0,0));
        return new OutboxWorker(store,publisher,settings,metrics,() -> {});
    }

    private OutboxWorker worker(OutboxSettings config, OutboxPublisher publisher, Runnable closeResources) {
        when(store.backlog(tenant)).thenReturn(new OutboxStore.Backlog(Map.of(),0,0));
        return new OutboxWorker(store,publisher,config,metrics,closeResources);
    }

    private OutboxWorker worker(OutboxSettings config, OutboxPublisher publisher, Runnable closeResources,
                                Semaphore capacity) {
        when(store.backlog(tenant)).thenReturn(new OutboxStore.Backlog(Map.of(),0,0));
        return new OutboxWorker(store,publisher,config,metrics,closeResources,capacity);
    }

    private OutboxSettings settings(Duration pollInterval, Duration publicationTimeout,
                                    Duration shutdownTimeout, int concurrency) {
        return new OutboxSettings(List.of(tenant),concurrency,concurrency,3,pollInterval,publicationTimeout,
                publicationTimeout.plusSeconds(6),Duration.ofSeconds(5),Duration.ofMillis(10),Duration.ofSeconds(1),
                shutdownTimeout);
    }

    private static <T> T field(OutboxWorker worker, String name, Class<T> type) {
        try {
            var field=OutboxWorker.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(worker));
        } catch(ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }


    @SuppressWarnings("unchecked")
    private static Set<Thread> controllers(OutboxWorker worker) {
        return (Set<Thread>)field(worker,"controllers",Set.class);
    }

    private static void pollTenant(OutboxWorker worker) {
        try {
            var method=OutboxWorker.class.getDeclaredMethod("pollTenant");
            method.setAccessible(true);
            method.invoke(worker);
        } catch(ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test void lifecycleIsIdempotentTerminalAndClosesResourcesOnce() {
        var closed=new AtomicInteger(); var callback=new AtomicBoolean();
        try(var worker=worker(settings,(event,timeout) -> PublicationOutcome.ACKNOWLEDGED,closed::incrementAndGet)) {
            worker.start(); worker.start();
            assertThat(worker.isRunning()).isTrue();
            worker.stop(() -> callback.set(true));
            assertThat(worker.isRunning()).isFalse(); assertThat(callback).isTrue();
            assertThatIllegalStateException().isThrownBy(worker::start)
                    .withMessage("Outbox worker cannot restart after shutdown");
            worker.close(); worker.close();
            assertThat(closed).hasValue(1);
        }
    }

    @Test void stoppedPollTenantReturnsBeforeAccessingStore() {
        try(var worker=worker((event,timeout) -> PublicationOutcome.ACKNOWLEDGED)) {
            pollTenant(worker);
            verifyNoInteractions(store);
        }
    }

    @Test void pollSkipsClaimWhenCapacityIsTakenAfterPermitSnapshot() throws Exception {
        var attempted=new CountDownLatch(1);
        var config=settings(Duration.ofMinutes(1),Duration.ofSeconds(1),Duration.ofMillis(100),1);
        var racedCapacity=new Semaphore(0) {
                @Override public int availablePermits() { return 1; }
                @Override public boolean tryAcquire(int permits) { attempted.countDown(); return false; }
        };
        try(var worker=worker(config,(event,timeout) -> PublicationOutcome.ACKNOWLEDGED,() -> {},racedCapacity)) {
            worker.start();
            assertThat(attempted.await(5,TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(store);
        }
    }

    @Test void shutdownAfterDispatchGateSkipsBacklogRefresh() {
        var workerReference=new AtomicReference<OutboxWorker>();
        when(store.claim(tenant,1)).thenReturn(new AbstractList<>() {
            @Override public OutboxStore.Claim get(int index) { throw new IndexOutOfBoundsException(index); }
            @Override public int size() { return 0; }
            @Override public Iterator<OutboxStore.Claim> iterator() {
                workerReference.get().stop();
                return Collections.emptyIterator();
            }
        });
        var config=settings(Duration.ofMinutes(1),Duration.ofSeconds(1),Duration.ofMillis(100),1);
        try(var worker=worker(config,(event,timeout) -> PublicationOutcome.ACKNOWLEDGED,() -> {})) {
            workerReference.set(worker);
            worker.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> !worker.isRunning());
            verify(store,never()).backlog(tenant);
        }
    }

    @Test void pollFailureAfterShutdownDoesNotCountAsLiveWorkerError() {
        var workerReference=new AtomicReference<OutboxWorker>();
        when(store.claim(tenant,1)).thenReturn(List.of());
        when(store.backlog(tenant)).thenAnswer(invocation -> {
            workerReference.get().stop();
            throw new DataAccessResourceFailureException("shutdown during backlog read");
        });
        var config=settings(Duration.ofMinutes(1),Duration.ofSeconds(1),Duration.ofMillis(100),1);
        try(var worker=new OutboxWorker(store,(event,timeout) -> PublicationOutcome.ACKNOWLEDGED,config,metrics,() -> {})) {
            workerReference.set(worker);
            worker.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> !worker.isRunning());
            assertThat(metrics.getMeters()).noneMatch(meter -> meter.getId().getName().equals("outbox.errors"));
        }
    }

    @Test void rejectedTransportSubmissionBecomesUnknownAndReleasesCapacity() {
        var config=settings(Duration.ofMinutes(1),Duration.ofSeconds(1),Duration.ofMillis(100),1);
        when(store.claim(tenant,1)).thenReturn(List.of()).thenReturn(List.of(claim));
        when(store.finish(any(),any(),any())).thenReturn(false);
        var publishes=new AtomicInteger();
        try(var worker=worker(config,(event,timeout) -> {
            publishes.incrementAndGet(); return PublicationOutcome.ACKNOWLEDGED;
        },() -> {})) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).claim(tenant,1));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).backlog(tenant));
            field(worker,"transports",ExecutorService.class).shutdownNow();
            worker.pollOnce();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(metrics.get("outbox.attempts").tag("outcome","UNKNOWN").counter().count()).isEqualTo(1));
            verify(store).finish(eq(claim),eq(PublicationOutcome.UNKNOWN),any());
            assertThat(metrics.get("outbox.finalizations").tag("result","FENCED").counter().count()).isEqualTo(1);
            assertThat(publishes).hasValue(0);
        }
    }

    @Test void nullPublisherOutcomeIsUnknownAndFencedFinalizationIsMeasured() {
        when(store.claim(tenant,1)).thenReturn(List.of(claim)).thenReturn(List.of());
        when(store.finish(any(),any(),any())).thenReturn(false);
        try(var worker=worker((event,timeout) -> null)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(store).finish(eq(claim),eq(PublicationOutcome.UNKNOWN),any()));
            assertThat(metrics.get("outbox.attempts").tag("outcome","UNKNOWN").counter().count()).isEqualTo(1);
            assertThat(metrics.get("outbox.finalizations").tag("result","FENCED").counter().count()).isEqualTo(1);
        }
    }

    @Test void shutdownDeadlineAndInterruptionLeaveAttemptsForRecovery() throws Exception {
        var otherClaim=new OutboxStore.Claim(new Publication(UUID.randomUUID(),tenant,UUID.randomUUID(),"event",1,"{}"),
                UUID.randomUUID(),1);
        var config=settings(Duration.ofMinutes(1),Duration.ofSeconds(30),Duration.ofMillis(1),2);
        var entered=new CountDownLatch(2);
        when(store.claim(tenant,2)).thenReturn(List.of(claim,otherClaim));
        try(var worker=worker(config,(event,timeout) -> {
            entered.countDown(); new CountDownLatch(1).await(); return PublicationOutcome.ACKNOWLEDGED;
        },() -> {})) {
            worker.start();
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            worker.stop();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(metrics.timer("outbox.processing").count()).isEqualTo(2));
        }
    }

    @Test void interruptedControllerDoesNotFinalizeAttempt() throws Exception {
        var config=settings(Duration.ofMinutes(1),Duration.ofSeconds(30),Duration.ofSeconds(5),1);
        when(store.claim(tenant,1)).thenReturn(List.of(claim));
        var entered=new CountDownLatch(1);
        try(var worker=worker(config,(event,timeout) -> {
            entered.countDown(); new CountDownLatch(1).await(); return PublicationOutcome.ACKNOWLEDGED;
        },() -> {})) {
            worker.start();
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var controllers=controllers(worker);
            await().atMost(Duration.ofSeconds(5)).until(() -> !controllers.isEmpty());
            for(Object controller:controllers) ((Thread)controller).interrupt();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(metrics.timer("outbox.processing").count()).isEqualTo(1));
            verify(store,never()).finish(any(),any(),any());
        }
    }

    @Test void shutdownInterruptSkipsJoinAndCancelsQueuedTasks() throws Exception {
        var config=settings(Duration.ofMinutes(1),Duration.ofSeconds(30),Duration.ofSeconds(5),1);
        when(store.claim(tenant,1)).thenReturn(List.of()).thenReturn(List.of(claim));
        var published=new AtomicInteger(); var blockerStarted=new CountDownLatch(1); var releaseBlocker=new CountDownLatch(1);
        try(var worker=worker(config,(event,timeout) -> {
            published.incrementAndGet(); return PublicationOutcome.ACKNOWLEDGED;
        },() -> {})) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).claim(tenant,1));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).backlog(tenant));
            var transports=(ThreadPoolExecutor)field(worker,"transports",ExecutorService.class);
            transports.execute(() -> { blockerStarted.countDown(); awaitIgnoringInterrupts(releaseBlocker); });
            assertThat(blockerStarted.await(5,TimeUnit.SECONDS)).isTrue();
            worker.pollOnce();
            await().atMost(Duration.ofSeconds(5)).until(() -> transports.getQueue().size()==1);
            transports.execute(() -> {});
            await().atMost(Duration.ofSeconds(5)).until(() -> transports.getQueue().size()==2);
            Thread.currentThread().interrupt();
            try {
                worker.stop();
            } finally {
                Thread.interrupted();
                releaseBlocker.countDown();
            }
            assertThat(published).hasValue(0);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(metrics.timer("outbox.processing").count()).isEqualTo(1));
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test void blockedClaimDoesNotDelayStopOrPublishAfterShutdown() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var returned=new CountDownLatch(1); var publishes=new AtomicInteger();
        when(store.claim(tenant,1)).thenAnswer(invocation -> {
            entered.countDown(); awaitIgnoringInterrupts(release); returned.countDown(); return List.of(claim);
        });
        try(var worker=worker((event,timeout) -> { publishes.incrementAndGet(); return PublicationOutcome.ACKNOWLEDGED; });
            var executor=Executors.newSingleThreadExecutor()) {
            worker.start(); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            Runnable stopWorker = worker::stop;
            var stopping=executor.submit(stopWorker);
            try {
                stopping.get(1,TimeUnit.SECONDS);
                assertThat(worker.isRunning()).isFalse();
            } finally { release.countDown(); }
            assertThat(returned.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(publishes).hasValue(0);
            verify(store,never()).finish(any(),any(),any());
        } finally { release.countDown(); }
    }

    @Test void lateAcknowledgmentCannotReplaceUnknownAndCapacityReturnsOnlyAfterExit() throws Exception {
        var release=new CountDownLatch(1); var finished=new CountDownLatch(1);
        var outcomes=new CopyOnWriteArrayList<PublicationOutcome>();
        when(store.claim(tenant,1)).thenReturn(List.of(claim)).thenReturn(List.of());
        when(store.finish(any(),any(),any())).thenAnswer(invocation -> { outcomes.add(invocation.getArgument(1)); return true; });
        try(var worker=worker((event,timeout) -> {
            try { awaitIgnoringInterrupts(release); return PublicationOutcome.ACKNOWLEDGED; }
            finally { finished.countDown(); }
        })) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(outcomes).containsExactly(PublicationOutcome.UNKNOWN));
            for(int i=0;i<10;i++) worker.pollOnce();
            verify(store,times(1)).claim(tenant,1);
            release.countDown(); assertThat(finished.await(5,TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store,atLeast(2)).claim(tenant,1));
            assertThat(outcomes).containsExactly(PublicationOutcome.UNKNOWN);
        } finally { release.countDown(); }
    }

    @Test void schedulerContinuesAfterDatabaseFailureWithoutLoggingExceptionDetails(CapturedOutput output) {
        when(store.claim(tenant,1)).thenThrow(new DataAccessResourceFailureException("SECRET_DATABASE_DETAIL"))
                .thenReturn(List.of(claim)).thenReturn(List.of());
        when(store.finish(any(),any(),any())).thenReturn(true);
        try(var worker=worker((event,timeout) -> PublicationOutcome.ACKNOWLEDGED)) {
            worker.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).finish(eq(claim),eq(PublicationOutcome.ACKNOWLEDGED),any()));
            assertThat(metrics.get("outbox.errors").tag("operation","poll").counter().count()).isEqualTo(1);
            assertThat(output.getAll()).doesNotContain("SECRET_DATABASE_DETAIL");
        }
    }
}
