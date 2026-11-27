## Phase

Phase 2: Reliable Asynchronous Event Processing using the Transactional Outbox Pattern.

## Why

Notification ingestion atomically persists durable outbox events, but no worker
publishes them. Introduce recoverable background processing while preserving
tenant isolation, auditing, and existing ingestion/idempotency guarantees.

Status: IMPLEMENTED AND ACCEPTED by the user on 2026-11-27; archival authorized.
No commit, deployment, or persistent infrastructure activation is authorized.

## What Changes

- Add explicit publication state, attempt accounting, expiring claims,
  ownership fencing, and bounded retry scheduling.
- Add automatic background polling independent of HTTP requests, scoped to trusted tenants,
  and a transport-independent publisher.
- Preserve the existing outbox UUID across every publication attempt.
- Add restricted worker database access and trusted tenant configuration.
- Add background audit updates, sanitized diagnostics, and bounded-cardinality metrics.
- Add PostgreSQL concurrency, recovery, migration, and security tests.

## Capabilities

### New Capabilities

- `outbox-publication`: Publication envelope, acknowledgment, and lifecycle.
- `outbox-worker-recovery`: Claims, leases, fencing, retries, and shutdown.
- `outbox-tenant-security`: Trusted tenant selection, privileges, and auditing.

### Modified Capabilities

None: this is the first OpenSpec change in this repository. Existing REST,
ingestion, idempotency, recipient, and authorization behavior remains a
compatibility requirement. No existing behavior is silently redefined.

## Impact

Outbox persistence, a forward-only migration after V4, worker configuration,
restricted-role provisioning, scheduling, tests, and operational documentation.
No external REST API contract changes are proposed.

## Out of Scope

Real broker adapters, email/push delivery, SSE, preference filtering, production
deployment, and unrelated authentication changes.

Phase 2 verifies a publication framework with simulated transport acknowledgment,
not external broker delivery.
