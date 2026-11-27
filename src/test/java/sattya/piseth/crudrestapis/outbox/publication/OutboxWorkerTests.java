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
            try { latch.await(); } catch(InterruptedException ignored) { /* Simulates stuck I/O. */ }
        }
    }
    private OutboxWorker worker(OutboxPublisher publisher) {
        when(store.backlog(tenant)).thenReturn(new OutboxStore.Backlog(Map.of(),0,0));
        return new OutboxWorker(store,publisher,settings,metrics,() -> {});
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
            var stopping=executor.submit(() -> { worker.stop(); });
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
