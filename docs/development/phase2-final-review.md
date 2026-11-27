# Phase 2 final review

Date: 2026-11-27. Scope: Reliable Asynchronous Event Processing using the Transactional Outbox Pattern.

The framework meets the seven concepts below under automated verification with
simulated transport acknowledgment. No outstanding implementation blocker was
found in this review. This is not evidence of external broker delivery or
production readiness. The worker remains disabled by default.

## Seven-concept assessment

| Concept | Implementation and verification |
|---|---|
| Background processing | Lifecycle-managed scheduler processes committed events without HTTP; enabled application test verifies ingestion/replay with an active worker. Poll failures do not stop subsequent scheduling or expose exception details. |
| Claims and concurrency | Short tenant-scoped transactions use row locks with SKIP LOCKED and fresh claim tokens. Independent workers receive disjoint claims; locked rows are skipped. |
| Leases and recovery | Persisted leases and attempt counts permit recovery. Finalization rechecks token and database time after obtaining its lock. Expired or superseded owners cannot mutate state. Blocked polling no longer blocks the shutdown gate. |
| Publisher abstraction | Immutable envelopes and four explicit outcomes separate transport from processing. Exactly one adapter is required when enabled. Only deterministic test adapters exist. Publication occurs outside database transactions. |
| Retry and failure | Capped exponential backoff with jitter, due-time selection, persistent attempt limits, permanent failure, and inspectable terminal state are tested. Late acknowledgments cannot replace UNKNOWN; failed database finalization remains recoverable. |
| At-least-once and idempotency | Stable event IDs survive retries and uncertain outcomes. Existing ingestion deduplication and audit identities remain intact. Duplicate external acceptance is possible; downstream consumers must deduplicate. Bounded retries do not guarantee eventual delivery. |
| Tenant security and auditing | Trusted configured tenants, separate restricted worker role, transaction-local context, forced RLS, immutable envelope grants, worker audit identity, and pooled connection reuse are tested. Original producer identity is preserved. |

The [scenario matrix](outbox-verification.md) maps all 38 scenarios to named tests.

## Findings addressed

Line references refer to the reviewed working tree under
`src/main/java/sattya/piseth/crudrestapis/outbox/publication/`.

| Severity | Finding, impact, correction, and evidence |
|---|---|
| Medium, fixed | `OutboxWorker.java:61,149`: polling previously held the monitor needed by stop across JDBC calls, allowing a database stall to exceed shutdown grace. A separate polling lock and short dispatch/shutdown gate now prevent this. A claim committed after shutdown remains recoverable without publication. `blockedClaimDoesNotDelayStopOrPublishAfterShutdown` reproduces and verifies the correction. |
| Medium, fixed | `OutboxConfiguration.java:29`: getIfUnique could select a primary adapter despite multiple registered adapters, violating the explicit destination contract. Configuration now counts all candidates and rejects ambiguity. `multipleAdaptersAreRejectedEvenWhenOneIsPrimary` verifies this. |
| Medium, fixed | `OutboxConfiguration.java:58`: startup validation omitted required SELECT and certain excess column/table/database privileges. Validation now checks these in addition to existing restrictions. `startupRejectsExcessColumnGrantsAndMissingReadPrivilege` verifies rejected configurations. This was an incomplete misconfiguration safeguard, not a demonstrated tenant-isolation exploit. |
| Preventive hardening | `OutboxWorker.java:102,136`: task-scoped FutureTask cancellation replaces raw thread interruption, avoiding an inferred thread-reuse race. Actual transport exit and controller completion both govern capacity release. `lateAcknowledgmentCannotReplaceUnknownAndCapacityReturnsOnlyAfterExit` verifies late-result and capacity behavior; the original thread-reuse race was not independently reproduced. |

Additional evidence covers sanitized polling failures, database failure after
simulated destination acceptance, execution of the actual worker provisioning
script, and rollback of an injected late V5 failure. All database mutations in
these checks target disposable Testcontainers databases.

## Executed verification

- Initial focused reproduction: 33 tests, three failures demonstrating the shutdown, adapter-selection, and privilege-validation defects before fixes.
- After fixes: `./gradlew.bat test --tests '*Outbox*Tests' --console=plain --max-workers=1` passed.
- Final full run: `./gradlew.bat test jacocoTestReport --console=plain --max-workers=1` passed: **165 tests, 21 suites, zero failures, errors, or skips**.
- JaCoCo: **97.09% lines** (768 covered, 23 missed), **88.50% branches** (354 covered, 46 missed). Coverage does not prove every concurrency interleaving.
- Fresh installation, populated V4-to-V5 upgrade, failed-V5 rollback, forced RLS, grants, and existing REST/security/idempotency regression tests are included.

The final scenario report also records OpenSpec and whitespace validation.

## Official guidance consulted

Context7 confirmed that Spring's [ObjectProvider](https://github.com/spring-projects/spring-framework/blob/main/spring-beans/src/main/java/org/springframework/beans/factory/ObjectProvider.java)
may resolve a primary candidate for getIfUnique. Current framework documentation
was available rather than an exact 7.0.10 index; the repository dependency was
compiled and tested locally.

PostgreSQL 18 documents that [privilege inquiry functions](https://www.postgresql.org/docs/18/functions-info.html)
account for table and column privileges; [GRANT](https://www.postgresql.org/docs/18/sql-grant.html)
defines SELECT, INSERT, UPDATE, REFERENCES, and TRIGGER privileges. These informed
the startup checks and restricted-role tests.

Oracle's Java 27 [FutureTask documentation](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/FutureTask.html)
defines task cancellation and interruption semantics. Cancellation alone does not
establish that an interrupt-ignoring callable has exited, so capacity remains held
until actual exit.

## Remaining boundaries and next milestone

- User accepted Phase 2 and authorized archival on 2026-11-27. All 44 tasks are complete; the three capability specs are synced to main specs.
- Persistent local V5 migration, worker provisioning, tenant configuration, and activation were not performed. Pool closure is separate from the controller shutdown grace; a stalled JDBC operation can outlive that grace.
- Actual process-kill recovery and production-scale load were not exercised. Tests simulate durable crash boundaries and lease expiry.
- An adapter that ignores timeout/interruption can occupy capacity indefinitely. The framework bounds resource consumption; adapters must obey their contract.
- Real broker confirmations, credentials/TLS, routing, consumer deduplication, and external delivery remain future integration work. Terminal requeue and retention are deferred; failed events remain inspectable.
- Mutable tenant context does not defend against arbitrary SQL compromise of the worker role; restricted grants limit available actions.
- SonarQube analysis and production operational/security checks were not run. Targeted worktree secret scanning is not a historical repository audit.

Recommended next milestone: specify and implement one broker adapter with durable
acknowledgment, consumer-side deduplication, and live failure/recovery tests.
The accepted change is archived at `openspec/changes/archive/2026-11-27-add-reliable-outbox-publisher/`.
No commit, push, deployment, or persistent infrastructure change is included.
