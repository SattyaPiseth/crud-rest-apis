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

The two integration test classes require Docker. They fail rather than silently
skip when a compatible Docker environment is unavailable.

## Coverage

| Suite | Main checks |
|---|---|
| NotificationDomainTests | Tenant consistency, idempotent read/archive methods, delivery transitions, expiry |
| SpringAuditorAwareTests | Missing/anonymous actors, JWT identity, scoped background actors |
| NotificationPersistenceTests | Migrations, auditing, ownership, database FKs, optimistic locking, Phnom Penh timestamps |
| NotificationIngestionTests | Request/event deduplication, conflicts, tenant/producer scope, atomic rollback, concurrent processing |

The ingestion suite uses six concurrent virtual-thread callers. A separate test
holds a database reservation open, verifies that a competing request waits, then
rolls back the first transaction to verify takeover. Fault injection applies only
to the disposable test database.

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
