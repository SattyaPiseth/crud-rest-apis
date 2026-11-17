# Testing and verification

[Documentation index](../README.md) · [Getting started](../getting-started.md)

## Prerequisites

Use JDK 27 and Docker Desktop with Linux containers. Run commands from the
repository root. Integration tests use disposable PostgreSQL containers and do
not need the `local` profile, Compose database, or local application secrets.

Do not globally enable the `local` profile for the test run.

## Run the complete suite

```powershell
.\gradlew.bat test bootJar
```

This runs tests and packages the application. To force test execution after a
review, even when Gradle considers tasks up to date:

```powershell
.\gradlew.bat test --rerun-tasks
```

## Run focused tests

Domain and audit unit tests:

```powershell
.\gradlew.bat test --tests '*NotificationDomainTests' --tests '*SpringAuditorAwareTests'
```

Ingestion integration tests:

```powershell
.\gradlew.bat test --tests '*NotificationIngestionTests'
```

Persistence integration tests:

```powershell
.\gradlew.bat test --tests '*NotificationPersistenceTests'
```

Migration integration tests:

```powershell
.\gradlew.bat test --tests '*DatabaseMigrationIntegrationTests'
```

Restricted runtime-role integration test:

```powershell
.\gradlew.bat test --tests '*RestrictedDatabaseRoleTests'
```

All six integration test classes require Docker. They fail rather than silently
skip when a compatible Docker environment is unavailable.

## Coverage

| Suite | Main checks |
|---|---|
| NotificationDomainTests | Tenant consistency, idempotent read/archive methods, delivery transitions, expiry |
| NotificationCommandTests | Command validation and recipient canonicalization |
| NotificationLifecycleTests | Preference changes, outbox publication-state transitions, delivery terminal states |
| RequestFingerprintTests | Fixed v1 command/event hashes, semantic field sensitivity, canonical equivalence, digest-provider failure |
| SpringAuditorAwareTests | Missing/anonymous actors, JWT identity, scoped background actors |
| NotificationPersistenceTests | Migrations, auditing, ownership, database FKs, optimistic locking, Phnom Penh timestamps |
| NotificationIngestionTests | Request/event deduplication, conflicts, tenant/producer scope, atomic rollback, concurrent processing, independent transactions, expiry replay, stored-only preferences |
| DatabaseMigratorTests | Migration entry point and required environment validation |
| DatabaseMigrationIntegrationTests | Fresh/upgrade migration, failed V3 rollback, restricted non-inheriting migration role, V4 privileges |
| RestrictedDatabaseRoleTests | Real ingestion/replay as crud_app; metadata/DDL access denied; all forced RLS policy catalog entries, cross-tenant reads/writes/ownership changes, missing/malformed context, pool reuse and concurrent contexts |
| NotificationApiTests | Signed-JWT authentication, scope/tenant/owner isolation, validation, replay, auditing, pagination, stale versions, safe rollback, live HTTP/1.1 chunked body-limit rejection, and status-only actuator probes |
| DatabaseUnavailableApiTests | Stops disposable PostgreSQL and verifies a real connection-acquisition failure returns 503 |
| ApiConfigurationTests | JWK URI allow/deny and startup validation, bearer challenge status, disabled routes, and streamed body-size boundaries with missing/misreported lengths |
| ApiErrorsTests | Native/wrapped error classification, permanent SQL failures, correlation IDs |
| CrudRestApisApplicationTests | Main method delegates to SpringApplication |

### Acceptance contract regression tests

The ingestion suite exercises the real proxied service and PostgreSQL transactions:

- `acceptedIngestionSurvivesOuterTransactionRollback`: flushes an outer write,
  accepts ingestion, rolls back the outer transaction, and verifies the outer
  write is absent while the notification, inbox, outbox and receipt remain replayable.
- `ingestionCannotSeeOuterUncommittedRecipientButCanRetryAfterCommit`: flushes a
  recipient in an outer transaction; independent ingestion rejects it without
  leaving receipts. After the outer commit, the same key succeeds.
- `acceptedRequestAndEventReplayAtAndAfterExpiryButNewKeysFail`: accepts an event
  before expiry, advances the injected clock to the exact expiry boundary and
  beyond, and checks both request and event replay. New keys fail at both times,
  with all business and receipt row counts unchanged.
- `disabledInAppPreferenceDoesNotSuppressStageOneInboxCreation`: persists a
  disabled matching preference and verifies acceptance and replay still produce
  exactly one inbox item. This protects current behavior, not future filtering.

The suite overrides the application `Clock` with `@MockitoBean` and initializes
it before every test to `2030-01-01T00:00:00Z` in `Asia/Phnom_Penh`. Expiry tests
advance that clock explicitly; they do not sleep or depend on the machine date.
The service, audit provider, transaction manager, and database remain real.

Run these tests together using the ingestion command above. No test-count or
passing-build claim is embedded here; inspect the reports from your actual run.

The ingestion suite uses six concurrent virtual-thread callers. A separate test
holds a database reservation open, verifies that a competing request waits, then
rolls back the first transaction to verify takeover. Fault injection applies only
to the disposable test database.

### Remaining foundation regression coverage

Recipient concurrency tests use `pg_blocking_pids` to establish actual database
waits before releasing the blocker; they do not infer ordering from sleeps.
When deactivation locks first, waiting ingestion rejects the now-inactive
recipient and leaves no business rows or receipts. When ingestion locks first,
a test-only outbox table lock pauses it after recipient locking; deactivation
waits for ingestion to commit, and the accepted notification remains replayable.
Blockers are released in `finally` blocks before executor shutdown.

The restricted-role suite provisions an isolated schema and owner default grants,
runs the real migrator as the owner, and starts the application with `crud_app`
credentials and runtime Flyway disabled. It verifies request/event replay and
row counts, then checks SQLSTATE `42501` for rejected CREATE, ALTER, and owner-role
escalation. This tests the runtime privilege boundary; it does not execute the
Compose secret/provisioning scripts or validate deployment networking.

Persistence tests require foreign-key SQLSTATE `23503` and an optimistic-lock
cause, then verify rejected writes are absent and the winning state survives.
Fingerprint fixtures pin the v1 command and event digests independently of the
production encoder. Do not update those expected hashes merely to accept an
encoding change: retained receipts require an explicit compatibility strategy.

These checks establish specific correctness properties. They do not establish
production throughput, high availability, or end-to-end Kafka delivery guarantees.

## Reports

After a run, inspect:

| Output | Local path |
|---|---|
| HTML test report | `build/reports/tests/test/index.html` |
| JUnit XML results | `build/test-results/test/` |
| Packaged application | `build/libs/` |

Generated reports and build outputs are ignored by Git. The latest run's report
is the source of truth for test counts and failures.

## Troubleshooting

- For Docker discovery failures, check Docker Desktop's engine and permissions.
- For initial download failures, check access to dependency registries and image registries.
- For migration failures, inspect the first underlying database exception.
- For timestamp failures, inspect the injected clock, JSON customizer and database
  session timezone; do not shift stored instants manually.
- For concurrency failures, inspect lock/transaction errors and the fixture's
  synchronization before changing timeouts.

See [ingestion transaction semantics](idempotent-ingestion.md#transaction-and-concurrency-contract)
and [database operations](../operations/database.md) for related context.

## REST API integration tests

```powershell
.\gradlew.bat test --tests '*NotificationApiTests'
```

This suite enables the API against disposable PostgreSQL and a loopback JWK server.
It signs real RS256 JWTs and uses the production decoder and security filter chain;
no mocked authentication is injected. Tokens with invalid signatures, issuer,
audience, tenant claims, expiry, or missing required expiry are rejected. It also
checks missing producer identity, scope enforcement, active membership, cross-tenant
and cross-recipient reads/writes, audited creation and updates, idempotency,
pagination, stale versions, and sanitized errors with atomic rollback.
The identity provider itself and production network/TLS configuration are not tested.

### REST hardening regressions

The signed-token suite additionally verifies standard bearer challenge parameters,
malformed claims, future not-before, `scp` arrays, independent read/write scopes,
state-only mutation responses, and denied unsupported routes. Validation covers
blank/oversized keys, recipient bounds/nulls, required nonnegative versions and media types.
Two concurrent HTTP mutations are held at their database UPDATEs to establish
that only one version-zero update commits (200/409). Another test proves the
explicit authorize-at-check-time behavior during recipient deactivation.
A real lock timeout produces 503, no partial rows, and a successful same-key retry.
Captured failure logs must omit database row detail and the injected constraint name.
Parameterized configuration tests exercise the JWK URI allow/deny matrix. Signed
JWT tests cover RS256 acceptance, RS512/ES256/HS256/none rejection, invalid
signatures, expiration, future `nbf`, issuer/audience mismatch, and a redirecting
JWK endpoint whose target must never be requested. Request-limit tests cover
0/1/128/256-KiB accepted bodies, 256-KiB+1/512-KiB/1-MiB rejection, absent and
underreported lengths, complete-body forwarding at the limit, and an actual
unknown-length HTTP/1.1 chunked upload returning 413 before parsing or persistence.

`DatabaseUnavailableApiTests` uses a separate disposable database, stops it and
evicts pooled connections before submitting a request. It injects authentication
only to isolate the transaction-start failure; signature validation remains covered
by `NotificationApiTests`. It is not a production failover test.
The RLS tests use PostgreSQL 18 and the restricted `crud_app` role; the general
API integration suite's PostgreSQL container uses its superuser fixture and is
not the RLS test.

## CI coverage and limits

The checked-in `Jenkinsfile` runs `./gradlew --no-daemon test jacocoTestReport`
on JDK 27, requires Docker for Testcontainers, archives JUnit/JaCoCo results, and
then invokes the configured SonarQube quality gate. The full Gradle test task
includes the JWT/JWK, RLS/restricted-role, migration, request-size, and API error
regressions described above; there are no separate duplicated security jobs.
This repository has no production deployment workflow or ingress-manifest
validation. The Jenkins/Sonar pipeline was inspected but cannot be independently
executed here because its Jenkins shared library and SonarQube installation are
external. No dependency vulnerability scanner is configured in the Gradle build.
