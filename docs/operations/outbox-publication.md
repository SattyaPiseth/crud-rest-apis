# Outbox worker operations

[Development design](../development/outbox-publication.md) | [Database operations](database.md)

## Current activation boundary

The worker is disabled by default. No real transport adapter is shipped. Do not turn
it on merely to drain persistent local events: without an explicit adapter startup
fails. Deterministic adapters exist only in tests and must not be promoted to runtime.
No persistent database or Keycloak changes were performed by this implementation.

## Migration and least privilege

Apply all migrations through V5 using the existing separate migration login during
an authorized maintenance window. V5 adds lifecycle columns, consistency constraints,
and tenant-leading due/lease indexes. Existing published_at rows become PUBLISHED;
other rows become PENDING. Existing identity, payload, and audit values are preserved.

The backfill creates a temporary policy only for the current migration identity and
drops it in the same Flyway transaction. ENABLE/FORCE RLS and the runtime policy are
never disabled. The migration role must be the schema owner (SET ROLE crud_owner).
Validate history and the absence of outbox_v5_backfill after migration. V1-V4 are unchanged.
V5 takes table locks: rehearse upgrade time and backup/restore before production.

After V5, an authorized database administrator may run
`docker/postgres/provision-outbox-worker.sql` against the intended database. It is a
one-time psql script, not a Compose auto-init script and not an application startup task.
Supply OUTBOX_DB_PASSWORD through the administrator's environment, without logging it.
The script creates crud_outbox with no role inheritance, dangerous attributes, or
memberships, and grants only schema usage, outbox SELECT, and column-level lifecycle
UPDATE. It intentionally fails if the role already exists; inspect existing grants
rather than replacing them blindly. For databases named differently from
crud_rest_apis, review the CONNECT grant before use.

Worker credentials must not be the API or migration credentials. Never grant table-wide
UPDATE, INSERT/DELETE, unrelated-table access, schema ownership, or BYPASSRLS. Startup
checks role attributes, memberships, RLS, required SELECT and lifecycle UPDATE grants,
and forbidden table/column INSERT, REFERENCES, TRIGGER, and identity updates.
Registration of multiple adapters fails even if one is marked primary. Recheck grants operationally
because they can change after startup.

## Configuration

| app.outbox property | Default | Meaning |
|---|---|---|
| enabled | false | Explicit worker activation |
| tenants | required when enabled | Approved UUID list, maximum 1000; backend configuration only |
| jdbc-url | required when enabled | Worker JDBC endpoint; no credentials embedded in URL |
| password | required when enabled | Secret for fixed username crud_outbox |
| concurrency | 4 | Maximum actual in-flight transport calls, 1..32 |
| batch-size | 1 | Per-tenant claim limit, 1..concurrency |
| max-attempts | 5 | Persisted acquisition limit, 1..1000 |
| poll-interval | 1s | One tenant considered per tick |
| publication-timeout | 10s | Maximum acknowledgment wait |
| lease-duration | 30s | Claim ownership lifetime |
| finalization-margin | 5s | Must be at least 5 seconds |
| initial-retry | 1s | Initial exponential cap before jitter |
| max-retry | 5m | Maximum retry cap |
| shutdown-timeout | 15s | Controller shutdown grace period |

Durations must be 1ms..1 day; lease-duration must exceed publication-timeout plus
finalization-margin, and initial-retry must not exceed max-retry. Equal jitter chooses
from half the capped exponential delay through the full cap, with a 1ms minimum.
Do not increase database timeouts without reviewing the lease margin.

Set APP_OUTBOX_ENABLED, APP_OUTBOX_TENANTS, APP_OUTBOX_JDBC_URL, and
APP_OUTBOX_PASSWORD outside source control. Advanced duration/capacity options can be
supplied under app.outbox in an external YAML file. Environment-variable forms of
Binder-managed hyphenated options omit the hyphen, e.g. APP_OUTBOX_MAXATTEMPTS.
The approved tenant list is an operator-maintained trust decision; it is never derived
from request bodies or from an unrestricted scan of tenant rows.

## Inspection and recovery

Inspect through the restricted worker connection inside an explicit transaction:

```sql
BEGIN;
-- Bind the approved tenant UUID as a parameter; do not copy an untrusted payload value.
SELECT set_config('app.tenant_id', :approved_tenant_uuid, true);
SELECT id, state, attempt_count, next_attempt_at, lease_until, failure_code,
       created_at, updated_at, created_by, updated_by, version
FROM app.outbox_event
ORDER BY created_at, id;
COMMIT;
```

This is parameterized SQL notation for a trusted SQL client, not a literal psql script.
Do not retrieve payloads for routine health checks. Missing tenant context fails closed.

For transient worker outages, restore connectivity and let leases expire. Do not reset
claims or attempt counts manually while workers may still be running. FAILED events
remain inspectable; Phase 2 has no authorized requeue operation. Diagnose the bounded
failure category and design an audited recovery action separately. Retention/deletion
of terminal rows is also outside this phase.

Disable the worker and restart to stop processing. Keep V5 installed: schema rollback
is not automatic. Do not run an older application path that writes published_at
without the new lifecycle. Backup restoration is a separately rehearsed operational
procedure, not a substitute for idempotent downstream consumers.

## Trust limits and future broker gate

Mutable transaction tenant settings protect normal application flows but cannot
isolate tenants from arbitrary SQL execution as the worker role. The allowlist is
application-level authorization. The dedicated role limits the available operations.

Before a real broker adapter is enabled, verify durable acknowledgment, timeout and
interruption behavior, destination authorization, TLS/credentials, stable event IDs,
consumer deduplication, shutdown, monitoring, rate limits, and controlled failure tests.
Production deployment and local activation require separate authorization.
