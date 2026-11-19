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

All seven integration test classes require Docker. They fail rather than silently
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
| RestrictedRoleHttpTests | Real HTTP with RS256 tokens and a `crud_app` datasource; forced RLS, cross-tenant denial, replay, auditing, one-connection reuse, configurable role source and independent audience |
| NotificationApiTests | Signed-JWT authentication, scope/tenant/owner isolation, validation, replay, auditing, pagination, stale versions, safe rollback, live HTTP/1.1 chunked body-limit rejection, and status-only actuator probes |
| DatabaseUnavailableApiTests | Stops disposable PostgreSQL and verifies a real connection-acquisition failure returns 503 |
| ApiConfigurationTests | JWK URI allow/deny and startup validation, bearer challenge status, disabled routes, role-source defaults/blank rejection, API-write-only buffering, and streamed body-size boundaries with missing/misreported lengths |
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

### Local Keycloak/PostgreSQL lifecycle verification

The local E2E run on 2026-11-19 used a dedicated confidential
`notification-e2e-producer` service account, not the recipient's Postman client.
Its Client Credentials token was checked locally for the expected realm issuer,
`notification-api` audience, fixed tenant and `producer_id` claims, expiry, and
only the `notifications:write` API role. The secret remains in the ignored
`.secrets/notification-e2e-producer.env`; never print or share that file or its
access token.

The persistent local database was at Flyway V2 when first inspected. The API
was stopped and `scripts/Migrate-LocalDatabase.ps1` applied V3 and V4 using its
temporary restricted migration login. Afterward, all eight tenant-owned tables
had forced RLS and tenant policies; `crud_app` saw zero rows without context,
the authorized tenant's row with its context, and zero rows with a different
tenant context. A cross-tenant insert was denied and rolled back, and runtime
access to Flyway history was denied. The actual tenant identifiers are omitted.

Using the migrated database and loopback-only API, producer ingestion returned
201, same-key/same-payload replay returned 200 with the original ID, changed
payload returned 409, unknown recipient returned 400, and producer access to
the inbox returned 403. At that historical post-migration checkpoint, the database had one notification,
recipient association, outbox event, and ingestion receipt. A subsequent read-only
inspection on 2026-11-19 found two of each for the approved tenant, with both
inbox items read and archived at version 2. These counts are observations at
specific checkpoints, not permanent expected totals.
Temporary clients also verified 403 for a token with `client_id` but no
`producer_id`, 403 for inbox-only roles attempting ingestion, and 401 for a
wrong audience or issuer; temporary clients were removed.

The local database has only one provisioned tenant and no inactive recipients,
so a live request between two existing tenants and an inactive-recipient request
were not possible without inventing fixtures. Cross-tenant access and active
membership rejection are covered by the disposable-database integration tests.
The recipient's fresh Postman token subsequently returned HTTP 200 for inbox
list, item detail, read, and archive, and HTTP 403 for attempted notification
ingestion. A read-only database check confirmed read/archive timestamps, version
2, and the same-tenant notification association after those requests. That initial report
did not include a GET list/detail performed after archive; the automated
`NotificationApiTests.readArchiveVersionAndPaginationContract` test verifies
that archived items leave the list but remain retrievable by ID. No JWT or
credential was shared. See the [identity broker guide](identity-broker.md) for
the current local service notes.

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
on JDK 27, checks out the exact revision selected by the Jenkins job's SCM, requires
Docker for Testcontainers, archives JUnit/JaCoCo results, and then invokes the
configured SonarQube quality gate. Configure the Jenkins `shared-library` global
library's default version as an approved immutable commit SHA and disable version
overrides; its repository and approved SHA are managed outside this repository.
The full Gradle test task
includes the JWT/JWK, RLS/restricted-role, migration, request-size, and API error
regressions described above; there are no separate duplicated security jobs.
This repository has no production deployment workflow or ingress-manifest
validation. The Jenkins/Sonar pipeline was inspected but cannot be independently
executed here because its Jenkins shared library and SonarQube installation are
external. No dependency vulnerability scanner is configured in the Gradle build.

### Signed HTTP with the restricted runtime database role

```powershell
.\gradlew.bat test --tests '*RestrictedRoleHttpTests'
```

This suite migrates disposable PostgreSQL as an administrator acting as
`crud_owner`, then starts the application on a random HTTP port with the datasource
logged in as `crud_app` (neither superuser nor `BYPASSRLS`). It signs RS256 JWTs
and serves a local public JWKS; authentication is not mocked. The role-source
client differs from both its default and the audience. Tests cover selected-client
roles, ignored other-client roles, additive scopes, and rejection of the wrong audience.
Signed-token cases also cover malformed nested role claims, mixed-type arrays,
and realm-only roles: invalid structures grant no authority, valid string roles
still work, and valid scopes remain usable alongside malformed role claims.
Permission denials assert HTTP 403 and the `insufficient_scope` bearer challenge.

With a pool size of one, requests alternate tenants through creation, replay,
listing, detail and read/archive updates. Foreign objects are hidden, foreign
recipient ingestion fails, and the same idempotency key works independently for
each tenant. Creation and mutation use distinct JWT subjects. Database assertions
verify the exact creation identity on notifications, inbox items, outbox events,
and receipts; read and archive updates preserve that identity and record the
exact modifying identity. A separate unread-item archive proves that archive
itself updates auditing, independently of an earlier read. The untouched tenant's
inbox retains its original audit identity and version. Tests also verify one receipt per tenant,
transaction-local tenant cleanup and reuse of the same PostgreSQL backend.
This complements the lower-level privilege and RLS policy tests; it does not
provision Keycloak or exercise Google federation.

`ApiConfigurationTests` additionally proves that skipped routes do not open the
body stream or wrap the request, while every supported write route retains the
256-KiB limit. Existing missing/misreported-length and chunked HTTP tests remain.

### Local sign-off regression checks

`RestrictedRoleHttpTests` performs signed HTTP requests against PostgreSQL as
`crud_app`: archived items disappear from the default list, remain available to
the owner through detail, and stale read/archive mutations return 409. A complete
persisted-row comparison verifies rejected mutations change neither state nor
audit fields. This is executable automated evidence, not a fresh Postman replay.

The same suite runs the actual Keycloak shell guard in a disposable Linux
container with substituted CPU-file fixtures and a harmless startup sentinel.
It covers standard and alternative VBMI spellings, unaffected flags, empty and
unrecognized metadata, a missing file, and a read failure. No Keycloak process is
started by these tests. The guard fails closed for unsupported metadata; it is
not a Java CVE patch or proof of physical-host safety.

Live second-tenant and inactive-recipient checks still require approved fixtures.
The user subsequently confirmed that the guided Postman checks behaved as
expected: archived items were absent from the list, detail remained accessible,
and stale read/archive mutations returned 409 with unchanged state on subsequent
reads. This is user-reported manual evidence, separate from independently executed
automated HTTP tests and database inspection. No existing user was deactivated
and no additional persistent memberships were created for sign-off.

A full Gradle test run on 2026-11-19 passed 129 tests across 17 suites with no
failures, errors, or skips. JaCoCo reporting was not run in that verification.
Local development sign-off covers the ingestion and inbox API; production
deployment and outbox publication remain outside this completed milestone.
