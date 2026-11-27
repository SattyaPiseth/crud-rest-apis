# Outbox implementation verification

Date: 2026-11-27. Change: `add-reliable-outbox-publisher`.

## Summary

| Dimension | Result |
|---|---|
| Completeness | 44/44 tasks complete; user accepted Phase 2 and authorized archival on 2026-11-27 |
| Correctness | All 38 specification scenarios mapped below to implementation and automated evidence |
| Coherence | Separate worker pool, trusted tenant configuration, forced RLS, native audit/version maintenance, and unchanged API transaction manager |
| Delivery boundary | Verified processing framework with simulated transport acknowledgment; no real broker adapter |

No application security blocker was found in this scoped implementation review.
User acceptance was recorded on 2026-11-27; the accepted change is archived.
No files have been committed and no persistent infrastructure has been activated.

## Executed checks

```powershell
.\gradlew.bat test --tests '*OutboxStoreTests' --tests '*DatabaseMigrationIntegrationTests' --console=plain --max-workers=1
.\gradlew.bat test --tests '*Outbox*Tests' --console=plain --max-workers=1
.\gradlew.bat test jacocoTestReport --console=plain --max-workers=1
openspec.cmd validate add-reliable-outbox-publisher --strict --no-interactive
git diff --check
```

Final complete run: **165 tests, 21 suites, zero failures, zero errors, zero skips**.
JaCoCo: **768 covered / 23 missed lines (97.09%)** and
**354 covered / 46 missed branches (88.50%)**. These are observed results, not a
promise of complete path coverage. JVM emitted class-sharing and reflective
final-field-mutation warnings; neither caused test failure.

Reports: `build/reports/tests/test/index.html`,
`build/reports/jacoco/test/html/index.html`, and
`build/reports/outbox/claim-plan.txt` (generated, not committed).

The exact claim-query EXPLAIN on 5,001 synthetic rows selected BitmapOr with
outbox_due_idx and outbox_lease_idx, followed by ordering/LockRows/Limit. This
establishes index applicability for that dataset, not production throughput.

Migration evidence: fresh V1-V5 installation in the regression suites; populated
V4-to-V5 backfill through the schema owner; original payload/audit preservation;
eight forced-RLS tables; removal of the temporary migration-only policy; Flyway
validation and restricted-role SQLSTATE assertions. The actual worker provisioning
script ran successfully in disposable PostgreSQL; an injected late V5 failure
rolled back its columns, temporary policy, and data changes before a successful retry. Persistent local migration
state was not changed or re-verified in this implementation task.

Whitespace checks include the new untracked files. A targeted scan of changed and
untracked files found no Sonar tokens, JWT-shaped tokens, or private-key blocks.
Literal passwords in new tests are disposable container fixtures only. This is
not a comprehensive historical secret scan. SonarQube analysis was not executed.

## Evidence source files

- [Worker/store/configuration](../../src/main/java/sattya/piseth/crudrestapis/outbox/publication)
- [V5 migration](../../src/main/resources/db/migration/V5__outbox_publication.sql)
- [Outbox tests](../../src/test/java/sattya/piseth/crudrestapis/outbox/publication)
- [Existing ingestion regressions](../../src/test/java/sattya/piseth/crudrestapis/NotificationIngestionTests.java)
- [Existing restricted-role tests](../../src/test/java/sattya/piseth/crudrestapis/infrastructure/migration/RestrictedDatabaseRoleTests.java)

## Scenario traceability

### outbox-publication

| Scenario | Automated evidence | Implementation |
|---|---|---|
| Enabled worker processes committed events independently | `OutboxEnabledApplicationTests.ingestionAndReplayPreserveAuditsWithActiveWorker; OutboxStoreTests.workerPublishesOutsideTransactionsAndRecoversLostAcknowledgment` | `OutboxWorker.start; pollOnce; runAttempt` |
| Polling temporarily fails | `OutboxWorkerTests.schedulerContinuesAfterDatabaseFailureWithoutLoggingExceptionDetails` | `OutboxWorker.pollOnce` |
| Ingestion commits | `OutboxEnabledApplicationTests.ingestionAndReplayPreserveAuditsWithActiveWorker` | `NotificationIngestionService; OutboxStore.claim` |
| Ingestion rolls back | `NotificationIngestionTests.databaseFailureAfterBusinessWritesRollsBackBothLedgersAndCanBeRetried` | `NotificationIngestionService transaction` |
| Accepted request is replayed | `OutboxEnabledApplicationTests.ingestionAndReplayPreserveAuditsWithActiveWorker` | `Existing deduplication; immutable outbox identity` |
| Event is retried | `OutboxStoreTests.workerPublishesOutsideTransactionsAndRecoversLostAcknowledgment` | `Publication; OutboxStore.claim` |
| Destination acknowledges | `OutboxStoreTests.outcomesPreserveEnvelopeAndAudit [ACKNOWLEDGED]` | `OutboxStore.finish` |
| Outcome is unknown | `OutboxStoreTests.workerPublishesOutsideTransactionsAndRecoversLostAcknowledgment; timeoutKeepsCapacityUntilActualTransportExits` | `OutboxWorker.awaitOutcome; OutboxStore.finish` |
| Destination permanently rejects | `OutboxStoreTests.outcomesPreserveEnvelopeAndAudit [PERMANENT]` | `OutboxStore.target` |
| Database finalization fails after acceptance | `OutboxStoreTests.databaseFailureAfterAcceptancePreservesClaimForRecovery` | `OutboxStore.finish transaction; lease recovery` |
| Terminal event is polled | `OutboxStoreTests.metricsDescribeBacklogAndPublishedEventsAreNotClaimedAgain; retryEligibilityAndCrashExhaustionArePersistent` | `OutboxStore.claim state predicate` |
| Invalid transition is attempted | `OutboxStoreTests.invalidStateCombinationsRollbackAndContextClearsAfterFailure; outcomesPreserveEnvelopeAndAudit` | `V5 constraints; OutboxStore.finish` |
| Crash after acceptance | `OutboxStoreTests.workerPublishesOutsideTransactionsAndRecoversLostAcknowledgment; expiredAndSupersededWorkersCannotChangeAnyPersistedField` | `Unknown outcome and lease recovery; simulated failure boundaries` |
| Enabled without adapter | `OutboxConfigurationTests.enabledRequiresTrustedTenantsAndExactlyOneAdapter` | `OutboxConfiguration.outboxWorker` |
| Multiple adapters are registered | `OutboxConfigurationTests.multipleAdaptersAreRejectedEvenWhenOneIsPrimary` | `OutboxConfiguration.outboxWorker` |
| Worker remains disabled | `OutboxConfigurationTests.disabledNeedsNeitherDatabaseNorAdapter` | `Conditional worker creation; application.yaml` |

### outbox-tenant-security

| Scenario | Automated evidence | Implementation |
|---|---|---|
| Tenant not authorized for worker | `OutboxStoreTests.tenantAuthorizationAndPoolContextFailClosed` | `OutboxStore.inTenant; trusted settings` |
| Enabled without authorized tenants | `OutboxConfigurationTests.enabledRequiresTrustedTenantsAndExactlyOneAdapter; malformedSettingsFailWithoutEchoingInput` | `OutboxSettings.from` |
| Context missing | `OutboxStoreTests.tenantAuthorizationAndPoolContextFailClosed; RestrictedDatabaseRoleTests` | `V3 forced RLS; OutboxStore.inTenant` |
| Connection reused | `OutboxStoreTests.singleConnectionReusedAcrossTenantsAndRollbackDoesNotLeak; invalidStateCombinationsRollbackAndContextClearsAfterFailure` | `Transaction-local set_config on worker connection` |
| Cross-tenant finalization is attempted | `OutboxStoreTests.tenantAuthorizationAndPoolContextFailClosed` | `Tenant predicate and forced RLS` |
| Unrelated write attempted | `OutboxStoreTests.restrictedRoleCannotMutateIdentityUnrelatedDataOrPolicies` | `Column grants and OutboxConfiguration.verifyRole` |
| Event identity mutation attempted | `OutboxStoreTests.restrictedRoleCannotMutateIdentityUnrelatedDataOrPolicies` | `Restricted worker grants` |
| Worker privileges are misconfigured | `OutboxStoreTests.startupRejectsExcessColumnGrantsAndMissingReadPrivilege` | `OutboxConfiguration.verifyRole` |
| Worker changes state | `OutboxStoreTests.outcomesPreserveEnvelopeAndAudit; OutboxEnabledApplicationTests.ingestionAndReplayPreserveAuditsWithActiveWorker` | `Native SQL audit/version updates` |
| Publication fails | `OutboxStoreTests.adapterExceptionsBecomeUnknownWithoutLeakingSensitiveErrors; workerPublishesOutsideTransactionsAndRecoversLostAcknowledgment` | `Bounded outcome enum, sanitized logs, bounded metric tags` |

### outbox-worker-recovery

| Scenario | Automated evidence | Implementation |
|---|---|---|
| Workers compete | `OutboxStoreTests.concurrentWorkersReceiveDisjointClaims; lockedRowsAreSkippedWithoutWaiting` | `FOR UPDATE SKIP LOCKED transaction` |
| Worker crashes | `OutboxStoreTests.expiredAndSupersededWorkersCannotChangeAnyPersistedField; retryEligibilityAndCrashExhaustionArePersistent` | `Persisted claim and lease recovery` |
| Old worker resumes | `OutboxStoreTests.expiredAndSupersededWorkersCannotChangeAnyPersistedField; finalizationRechecksLeaseAfterWaitingForRowLock` | `Token and database-time fencing` |
| Event is reclaimed | `OutboxStoreTests.expiredAndSupersededWorkersCannotChangeAnyPersistedField` | `Fresh token for every claim` |
| Recoverable failure | `OutboxStoreTests.outcomesPreserveEnvelopeAndAudit [RETRYABLE, UNKNOWN]; OutboxConfigurationTests.backoffHasJitterAndOverflowSafeCap` | `RetryBackoff; OutboxStore.finish` |
| Retry is not yet due | `OutboxStoreTests.retryEligibilityAndCrashExhaustionArePersistent` | `Due-time predicate` |
| Attempt budget exhausted | `OutboxStoreTests.retryEligibilityAndCrashExhaustionArePersistent; finalFailureUsesPersistedAttemptCountNotSuppliedClaimMetadata` | `Persisted attempt count; exhausted-lease terminal update` |
| One tenant has sustained backlog | `OutboxStoreTests.tenantRoundRobinDoesNotSkipWaitingTenantWhenCapacityIsBusy` | `Round-robin cursor advances only with capacity` |
| Worker capacity is exhausted | `OutboxStoreTests.timeoutKeepsCapacityUntilActualTransportExits; OutboxWorkerTests.lateAcknowledgmentCannotReplaceUnknownAndCapacityReturnsOnlyAfterExit` | `Capacity released only after controller and actual transport completion` |
| Shutdown deadline expires | `OutboxStoreTests.timeoutKeepsCapacityUntilActualTransportExits; gracefulShutdownLeavesInterruptedAttemptRecoverable` | `SmartLifecycle stop; bounded wait and lease recovery` |
| Database polling stalls during shutdown | `OutboxWorkerTests.blockedClaimDoesNotDelayStopOrPublishAfterShutdown` | `OutboxWorker.stop; dispatch gate` |
| Failed event awaits recovery | `OutboxStoreTests.retryEligibilityAndCrashExhaustionArePersistent` | `FAILED excluded from normal claims; no requeue operation` |

## Remaining boundaries and next work

- Phase 2 is accepted and archived; no commit or push is authorized.
- Do not activate the persistent local worker without separately authorized V5,
  approved tenant configuration, restricted-role provisioning, and an actual adapter.
- Real broker acknowledgments, downstream deduplication, TLS/credentials, and
  delivery failure behavior require a later adapter milestone and live evidence.
- FAILED inspection is implemented; authorized requeue and retention are deferred.
- Fairness and bounded concurrency are tested locally, not load-tested at scale.
- Gauges are sampled and can be stale while transport capacity is exhausted.
- A compromised worker capable of arbitrary SQL can change the mutable tenant
  setting; the allowlist does not provide an independent database membership boundary.

Recommended next milestone: specify a real
broker adapter with durable acknowledgment and consumer-side deduplication tests.

## Final review checkpoint

The earlier 159-test checkpoint preceded the final seven-concept review. The
165-test result above includes the source corrections and additional regression
coverage described in [the final review](phase2-final-review.md). All 38 scenarios
are mapped above; crash tests simulate durable failure boundaries and lease
expiry, rather than killing a live operating-system process.

## Acceptance and archive

User approved Phase 2 and archival on 2026-11-27. The three capability specs are
synced to `openspec/specs/`; the [accepted change](../../openspec/changes/archive/2026-11-27-add-reliable-outbox-publisher/tasks.md)
records all 44 tasks complete. This administrative update does not change runtime
code or the full-suite evidence above.
