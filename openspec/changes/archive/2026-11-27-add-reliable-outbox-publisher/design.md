## Context

APPROVED FOR IMPLEMENTATION by the user on 2026-11-27.

Baseline inspected at commit 5bc398e:
- NotificationIngestionService persists notification, inbox, receipt, and outbox
  atomically and emits notification.created schema version 1. Its payload contains
  notificationId, recipientCount, and occurredAt.
- OutboxEvent inherits UUID, tenant, optimistic version, and audit fields; it has
  publishedAt but no claims, lease, attempts, retry schedule, or failure state.
- V3 forces RLS on eight tables; V4 restricts Flyway history access.
- TenantDatabaseContext uses transaction-local set_config through EntityManager.
- BackgroundActor supplies service identities through ScopedValue.
- crud_app has broad application-table DML, not outbox-only permissions.
- No tenant registry or publisher is implemented.

These are source findings, not a fresh verification of the persistent database.

## Phase definition

Phase 2: Reliable Asynchronous Event Processing using the Transactional Outbox Pattern.
The seven design pillars are automatic background processing, exclusive valid claims,
recoverable leases, a publisher abstraction, controlled retries and terminal failures,
at-least-once semantics with stable event identity, and tenant security with auditing.
The scheduler starts with the enabled worker lifecycle, independently of HTTP calls.

## Goals / Non-Goals

Goals: recoverable, bounded, tenant-authorized processing; explicit acknowledgment;
stable event identity; audited transitions; preservation of REST/idempotency behavior.

Non-goals: real broker integration, external delivery, strict event ordering,
production activation, preference filtering, and a public recovery API.

## Decisions

### Execution and trusted tenant selection

Use a worker component in the existing codebase, independently enabled and disabled
by default. Use a separate restricted worker datasource/transaction manager.
Tenant IDs come from an explicit operator-provisioned allowlist. Empty or malformed
configuration fails activation. No tenants or memberships are invented. Cycle through
approved tenants with bounded per-tenant work and bounded total execution capacity.

### Restricted role and transaction context

Provision a dedicated worker role with schema usage, outbox SELECT, and column-level
UPDATE grants for lifecycle, updated_at, updated_by, and version only. Grant no
INSERT/DELETE, unrelated DML, schema ownership, DDL, Flyway access, or BYPASSRLS.
Provision credentials separately from Flyway SQL; use restricted role integration tests.

Use the worker transaction's own connection to set transaction-local app.tenant_id.
Do not assume the existing EntityManager-based context helper targets the new
worker datasource. Verify context and statements share a transaction/connection.
Keep the API datasource and its transaction manager behavior unchanged.

### Claim, publish, finalize

Transaction A: establish tenant context, select a bounded eligible batch with
FOR UPDATE SKIP LOCKED, record fresh ownership tokens and expiring leases,
increment attempt counts, and commit.

Outside transaction: publish immutable envelopes and await acknowledgment or timeout.
Never hold database locks while publishing. Claim only available execution capacity.

Transaction B: reestablish tenant context and finalize using event ID, tenant ID,
PROCESSING state, claim token, and an unexpired lease as predicates. Zero affected
rows means ownership lost, not success. Use database time for lease/eligibility checks.
Every recovery acquisition uses a fresh token, including recovery on the same instance.

### Lifecycle and retry

Allowed transitions:
- PENDING -> PROCESSING
- RETRY -> PROCESSING when due
- PROCESSING -> PUBLISHED on acknowledged and fenced completion
- PROCESSING -> RETRY on recoverable/unknown outcome with budget remaining
- PROCESSING -> FAILED on permanent failure or exhausted attempts
- Expired PROCESSING -> new PROCESSING claim when budget remains
- Expired PROCESSING -> FAILED when budget is exhausted

PUBLISHED and FAILED are terminal for normal processing. The attempt count increments
on claim, including crash recovery; it measures acquired attempts, not proven sends.
Lease recovery/exhaustion changes must also be atomic and audited.

Use capped exponential backoff with bounded jitter and overflow-safe calculations.
Validate attempts, durations, concurrency, and batch bounds. Lease duration must exceed
publication timeout plus a finalization margin. Stop claiming during shutdown; bound
in-flight waiting and leave unfinished claims recoverable. Do not reset attempt budgets
on restart. Inspection is in scope; operator requeue implementation is deferred.

### Migration and compatibility

Add V5; do not edit V1-V4. Add state, attempt count, eligibility time, ownership token,
lease expiry, and bounded failure classification. Backfill published_at rows as
PUBLISHED and other rows as PENDING, preserving existing identity/payload/audit values.
Add state/lease/timestamp consistency constraints and tenant-leading eligibility indexes.
Test fresh installation and populated V4 upgrades; preserve RLS and history restrictions.

Keep ingestion insert compatibility through suitable initial defaults. Review the
existing unfenced markPublished method so the worker cannot bypass ownership checks.
Define deployment compatibility explicitly; no old publisher may bypass the new
lifecycle. Disabling the worker is the operational rollback, not reversing migrations.

### Envelope and adapter

Use outbox UUID as eventId. Include tenantId, aggregateId, eventType, schemaVersion,
and the existing payload unchanged across retries. Preserve notification.created v1.

The interface distinguishes acknowledged, retryable, permanent, and unknown outcomes.
Use deterministic adapters only in tests. An enabled worker without an adapter fails
closed; never install a default success/no-op adapter for persistent local events.
Phase 2 does not verify broker acknowledgment or end-user delivery.

### Audit and observability

Native worker SQL bypasses JPA auditing listeners. Explicitly preserve creation audit
and increment version, updated_at, and updated_by for every successful mutation.
Use service:outbox-publisher as the proposed trusted actor. Rejected stale updates
must not alter any persisted fields.

Measure state backlog, expired leases, attempt outcomes, oldest unpublished age,
and acknowledgment/processing latency across authorized tenants. Use only bounded
labels. Do not log payloads, tokens, credentials, or raw destination errors. Preserve
restricted actuator exposure; metrics implementation does not authorize public exposure.

## Risks / Trade-offs

- Database fencing cannot stop an expired worker's already-running external request.
- Lost acknowledgments can produce duplicates; consumers must deduplicate event IDs.
- Bounded retries do not guarantee eventual delivery.
- Best-effort oldest-eligible selection does not guarantee delivery order.
- Polling costs and fairness need bounded settings and query-plan evidence.
- The tenant allowlist and mutable tenant setting are trusted application controls,
  not protection against arbitrary SQL execution by a compromised worker identity.
- The dedicated role limits privileges but does not make the tenant setting immutable.
- Manual tenant authorization needs operational maintenance.
- Native SQL needs explicit audit/version maintenance and careful migration compatibility.

## Approval and verification gates

Approve tenant allowlist ownership, dedicated worker role/datasource, no strict ordering,
terminal inspection without requeue, and test-only adapters before implementation.
Record evidence per specification scenario; do not mark work complete from code alone.
Verify all existing API/security/idempotency regressions and restricted PostgreSQL tests.
Archive only after implementation and explicit acceptance approval.

## References

- https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md
- https://github.com/Fission-AI/OpenSpec/blob/main/docs/cli.md
- src/main/java/sattya/piseth/crudrestapis/outbox/domain/OutboxEvent.java
- src/main/java/sattya/piseth/crudrestapis/notification/application/NotificationIngestionService.java
- src/main/java/sattya/piseth/crudrestapis/shared/persistence/TenantDatabaseContext.java
- src/main/java/sattya/piseth/crudrestapis/shared/audit/BackgroundActor.java
- src/main/resources/db/migration/V1__notification_foundation.sql
- src/main/resources/db/migration/V3__tenant_row_security.sql
- src/main/resources/db/migration/V4__restrict_flyway_history.sql
- docker/postgres/init/10-app.sh

## Implementation clarifications

- Worker pool and transaction manager are privately owned by OutboxWorker/OutboxStore,
  not competing Spring DataSource/TransactionManager beans. The enabled-application
  test verifies the API still uses crud_app and JpaTransactionManager.
- V5 backfills through a temporary migration-identity-only policy, removed within
  the Flyway transaction. FORCE RLS and the runtime policy remain enabled.
- One tenant is considered per poll tick. Capacity exhaustion does not advance the
  waiting tenant, preventing timing-dependent starvation.
- Attempt capacity is retained until actual transport completion even after timeout.
- Backlog gauges are sampled, may be stale while capacity is exhausted, and include
  FAILED in oldest-unpublished age. There is no new public actuator exposure.
- Source implementation is complete only when backed by scenario evidence; persistent
  database activation and real broker acknowledgment are separate operational work.

## Final review corrections

- Polling no longer holds the lifecycle monitor across JDBC work. A short shared
  dispatch/stop gate prevents post-stop publication without delaying stop on DB I/O.
- Cancellation uses a FutureTask per attempt. Capacity is released from actual run
  completion, not Future completion, preserving the limit when adapters ignore interrupts.
- Adapter selection counts all candidates and rejects ambiguity even with a primary bean.
- Startup verifies outbox SELECT, column-level forbidden INSERT/REFERENCES, and TRIGGER
  as well as the previously checked role attributes, lifecycle grants, and RLS flags.
- Regression evidence includes the actual provisioning script, rollback of a failed V5
  migration, and failed database finalization after simulated destination acceptance.
