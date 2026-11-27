package sattya.piseth.crudrestapis.outbox.publication;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxConfigurationTests {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(OutboxConfiguration.class)
            .withBean(MeterRegistry.class,SimpleMeterRegistry::new);

    @Test void disabledNeedsNeitherDatabaseNorAdapter() {
        context.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(OutboxWorker.class));
        context.withPropertyValues("app.outbox.enabled=false").run(ctx ->
                assertThat(ctx).hasNotFailed().doesNotHaveBean(OutboxWorker.class));
    }
    @Test void invalidEnablementFailsClosed() {
        context.withPropertyValues("app.outbox.enabled=yes").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasMessageContaining("app.outbox.enabled must be true or false"));
    }
    @Test void enabledRequiresTrustedTenantsAndExactlyOneAdapter() {
        context.withPropertyValues("app.outbox.enabled=true").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasRootCauseMessage("Invalid app.outbox configuration; check tenant list and bounded settings"));
        context.withPropertyValues("app.outbox.enabled=true","app.outbox.tenants="+UUID.randomUUID()).run(ctx ->
                assertThat(ctx.getStartupFailure()).hasRootCauseMessage("Outbox requires exactly one explicit publication adapter"));
    }
    @Test void malformedSettingsFailWithoutEchoingInput() {
        for(var property:List.of("tenants=SECRET_BAD_TENANT","concurrency=0","batch-size=33","max-attempts=0",
                "publication-timeout=0ms","lease-duration=10s","max-retry=1ms","max-retry=86400001ms",
                "shutdown-timeout=2d","finalization-margin=1s")) {
            var env=new MockEnvironment().withProperty("app.outbox.tenants",UUID.randomUUID().toString());
            var parts=property.split("=",2); env.setProperty("app.outbox."+parts[0],parts[1]);
            assertThatIllegalArgumentException().isThrownBy(() -> OutboxSettings.from(env))
                    .withMessage("Invalid app.outbox configuration; check tenant list and bounded settings");
        }
    }
    @Test void backoffHasJitterAndOverflowSafeCap() {
        var settings=OutboxSettings.from(new MockEnvironment().withProperty("app.outbox.tenants",UUID.randomUUID().toString()));
        var low=new RetryBackoff(settings,() -> 0.0); var high=new RetryBackoff(settings,() -> 0.999999);
        assertThat(low.delay(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(high.delay(1)).isEqualTo(Duration.ofMillis(1000));
        assertThat(low.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMillis(150000));
        assertThat(high.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
        assertThatIllegalArgumentException().isThrownBy(() -> low.delay(0)).withMessage("Attempt must be positive");
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryBackoff(settings,() -> Double.NaN).delay(1))
                .withMessage("Jitter sample must be in [0,1)");
    }
    @Test void maximumRetryDurationCapsBackoffWithoutOverflow() {
        var environment = new MockEnvironment().withProperty("app.outbox.tenants", UUID.randomUUID().toString())
                .withProperty("app.outbox.initial-retry", "1ms").withProperty("app.outbox.max-retry", "24h");
        var settings = OutboxSettings.from(environment);
        assertThat(settings.maxRetry()).isEqualTo(Duration.ofDays(1));

        var minimumJitter = new RetryBackoff(settings, () -> 0.0);
        var maximumJitter = new RetryBackoff(settings, () -> Math.nextDown(1.0));
        assertThat(minimumJitter.delay(27)).isEqualTo(Duration.ofMillis(33_554_432));
        assertThat(minimumJitter.delay(28)).isEqualTo(Duration.ofHours(12));
        assertThat(minimumJitter.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(12));

        var maximumDelay = maximumJitter.delay(28);
        assertThat(maximumDelay).isEqualTo(Duration.ofDays(1));
        assertThat(maximumJitter.delay(Integer.MAX_VALUE)).isEqualTo(maximumDelay);
    }
    @Test void multipleAdaptersAreRejectedEvenWhenOneIsPrimary() {
        context.withPropertyValues("app.outbox.enabled=true","app.outbox.tenants="+UUID.randomUUID())
                .withBean("first",OutboxPublisher.class,() -> (event,timeout) -> PublicationOutcome.ACKNOWLEDGED,
                        definition -> definition.setPrimary(true))
                .withBean("second",OutboxPublisher.class,() -> (event,timeout) -> PublicationOutcome.PERMANENT)
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .hasRootCauseMessage("Outbox requires exactly one explicit publication adapter"));
    }

    @Test void workerClosesPoolWhenDatabaseInitializationFails() {
        context.withPropertyValues("app.outbox.enabled=true", "app.outbox.tenants=" + UUID.randomUUID(),
                        "app.outbox.jdbc-url=jdbc:postgresql://127.0.0.1:1/outbox", "app.outbox.password=test-only")
                .withBean(OutboxPublisher.class, () -> (event, timeout) -> PublicationOutcome.ACKNOWLEDGED)
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                        "Outbox database initialization failed; verify V5, restricted role, and connectivity"));
    }

    @Test void requiredJdbcPropertiesRejectMissingAndBlankValues() {
        var enabled = context.withPropertyValues("app.outbox.enabled=true", "app.outbox.tenants=" + UUID.randomUUID())
                .withBean(OutboxPublisher.class, () -> (event, timeout) -> PublicationOutcome.ACKNOWLEDGED);
        enabled.run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("app.outbox.jdbc-url is required"));
        enabled.withPropertyValues("app.outbox.jdbc-url=")
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("app.outbox.jdbc-url is required"));
        enabled.withPropertyValues("app.outbox.jdbc-url=jdbc:postgresql://127.0.0.1:1/outbox")
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("app.outbox.password is required"));
        enabled.withPropertyValues("app.outbox.jdbc-url=jdbc:postgresql://127.0.0.1:1/outbox", "app.outbox.password=")
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("app.outbox.password is required"));
    }

    @Test void verifyRoleRejectsUnsafeRoleAndUnrelatedTableAccess() {
        assertThatIllegalStateException().isThrownBy(() -> OutboxConfiguration.verifyRole(roleJdbc(false, null, null, null)))
                .withMessage("Worker database role is not restricted");
        assertThatIllegalStateException().isThrownBy(() -> OutboxConfiguration.verifyRole(roleJdbc(true, "notification", null, null)))
                .withMessage("Worker can access unrelated application data");
    }

    @Test void verifyRoleRequiresLifecycleGrantsAndProtectsImmutableFields() {
        assertThatCode(() -> OutboxConfiguration.verifyRole(roleJdbc(true, null, null, null)))
                .doesNotThrowAnyException();
        assertThatIllegalStateException().isThrownBy(() -> OutboxConfiguration.verifyRole(
                        roleJdbc(true, null, "claim_token", null)))
                .withMessage("Worker is missing required lifecycle grants");
        assertThatIllegalStateException().isThrownBy(() -> OutboxConfiguration.verifyRole(
                        roleJdbc(true, null, null, "id")))
                .withMessage("Worker can mutate immutable event fields");
    }

    private static JdbcTemplate roleJdbc(boolean restrictedRole, String accessibleUnrelatedTable,
                                         String missingLifecycleColumn, String mutableImmutableColumn) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("FROM pg_roles"), eq(Boolean.class))).thenReturn(restrictedRole);
        when(jdbc.queryForObject(contains("has_table_privilege(current_user,?"), eq(Boolean.class), any(), any()))
                .thenAnswer(invocation -> Objects.equals("app." + accessibleUnrelatedTable, invocation.getArgument(2)));
        Set<String> lifecycleColumns = Set.of("state", "attempt_count", "next_attempt_at", "claim_token", "lease_until",
                "failure_code", "published_at", "updated_at", "updated_by", "version");
        when(jdbc.queryForObject(contains("has_column_privilege"), eq(Boolean.class), any()))
                .thenAnswer(invocation -> {
                    String column = invocation.getArgument(2);
                    if (lifecycleColumns.contains(column)) return !column.equals(missingLifecycleColumn);
                    return column.equals(mutableImmutableColumn);
                });
        return jdbc;
    }

}
