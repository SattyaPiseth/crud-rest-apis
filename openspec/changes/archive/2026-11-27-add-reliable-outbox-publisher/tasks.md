## 1. Setup and approval

- [x] 1.1 Obtain authorization to initialize OpenSpec and write draft artifacts.
- [x] 1.2 Initialize once and preserve existing repository instructions.
- [x] 1.3 Create proposal, capability specs, design, and tasks for review.
- [x] 1.4 Confirm tenant authorization, worker role, ordering, and recovery decisions.
- [x] 1.5 Validate artifacts and obtain proposal/specification/design approval.

Implementation authorized on 2026-11-27. Phase 2 acceptance and archival authorized on 2026-11-27; commit and deployment remain unapproved.

## 2. Database and permissions

- [x] 2.1 Define lifecycle invariants and ownership predicates.
- [x] 2.2 Add V5 migration and compatibility backfill.
- [x] 2.3 Add tenant-scoped eligibility indexes and inspect query plans.
- [x] 2.4 Add separate worker role provisioning without embedded credentials.
- [x] 2.5 Test fresh installation, populated V4 upgrade, RLS, and grants.

## 3. Worker and transport

- [x] 3.1 Add validated disabled-by-default worker configuration.
- [x] 3.2 Implement trusted tenant scheduling and bounded capacity.
- [x] 3.3 Implement atomic claims and persisted attempt accounting.
- [x] 3.4 Implement lease recovery and ownership-checked finalization.
- [x] 3.5 Define immutable publication envelope and outcome contract.
- [x] 3.6 Implement acknowledgment, retry, permanent failure, and unknown outcomes.
- [x] 3.7 Implement bounded backoff, jitter, and shutdown.
- [x] 3.8 Add deterministic test-only transport adapters.

## 4. Security and auditing

- [x] 4.1 Maintain transaction-local context on the worker datasource.
- [x] 4.2 Preserve producer creation audit and update worker audit/version fields.
- [x] 4.3 Test denied unrelated writes and immutable event-column updates.
- [x] 4.4 Test unauthorized tenants, missing context, and pooled connection reuse.
- [x] 4.5 Add sanitized diagnostics and bounded-cardinality metrics.

## 5. Behavioral verification

- [x] 5.1 Test legal and rejected state transitions.
- [x] 5.2 Test concurrent claims using independent workers/connections.
- [x] 5.3 Test crash recovery and expired final-attempt exhaustion.
- [x] 5.4 Test stale finalization before and after replacement ownership.
- [x] 5.5 Test lost acknowledgment and duplicate publication with stable identity.
- [x] 5.6 Test retry boundaries, jitter bounds, permanent failures, and timeouts.
- [x] 5.7 Test tenant fairness, bounded capacity, and shutdown recovery.
- [x] 5.8 Verify no external publication occurs inside a database transaction.
- [x] 5.9 Run existing ingestion, inbox, authorization, and idempotency regressions.
- [x] 5.10 Test disabled defaults and enabled-without-adapter/configuration failures.

## 6. Acceptance

- [x] 6.1 Update configuration, migration, and operational documentation.
- [x] 6.2 Run full Gradle tests and JaCoCo.
- [x] 6.3 Run git diff --check and review for secrets/unrelated changes.
- [x] 6.4 Map every specification scenario to test evidence or an explicit limitation.
- [x] 6.5 Report simulated acknowledgment separately from real broker verification.
- [x] 6.6 Obtain acceptance approval before archiving.

Evidence: [outbox verification report](../../../../docs/development/outbox-verification.md).
Task 6.6 completed: user approved Phase 2 and archival on 2026-11-27. Do not commit or push.

## 7. Final seven-concept review

- [x] 7.1 Reproduce and fix shutdown blocking; retain bounded transport capacity with task-scoped cancellation.
- [x] 7.2 Enforce exactly one adapter, including primary-candidate ambiguity, and complete startup privilege checks.
- [x] 7.3 Add polling-error, late-acknowledgment, and failed-finalization recovery tests.
- [x] 7.4 Execute the actual provisioning script and verify late V5 migration rollback in disposable PostgreSQL.
- [x] 7.5 Rerun full regression/JaCoCo and reconcile final review evidence and documentation.
