# Notification Service

A Spring Boot case study implementing a tenant-aware notification domain,
auditing, transactional in-app ingestion, and database-backed deduplication.

Stage 1 provides the persistence and transactional ingestion foundation. Stage 2
adds an opt-in JWT-authorized REST API for ingestion and recipient inbox operations.

The repository and Gradle project retain the name `crud-rest-apis`.

## Start here

- [Getting started](docs/getting-started.md): prerequisites, tests, migrations, and local startup.
- [Documentation index](docs/README.md): find guidance by task.
- [ER diagram](docs/architecture/er-diagram.md): tables, relationships, and constraints.
- [Ingestion guide](docs/development/idempotent-ingestion.md): application-service examples and retry semantics.

## Current implementation

| Implemented | Remaining |
|---|---|
| Six domain entities and two deduplication receipt tables | Identity-provider deployment and token provisioning |
| Tenant-aware foreign keys and ownership-scoped lookup | Kafka listener and outbox publisher |
| Auditing and optimistic locking | Delivery workers, leases, retries, and provider adapters |
| Idempotent ingestion and transport-independent event consumer | Verified destinations, templates, and delivery-attempt history |
| Transactional outbox creation | SSE, production load testing, and retention automation |
| Preference persistence and enable/disable domain methods | Preference enforcement during ingestion or delivery |

The REST API supports notification creation/replay, inbox listing and retrieval,
and version-checked read/archive updates. HTTP remains denied by default; enable
the API with a trusted issuer, JWK URL, and audience as described in the
[REST API guide](docs/development/rest-api.md).

## Current behavior and guarantees

- A tenant/producer/idempotency key identifies one accepted notification while
  its receipt is retained. Equivalent retries return its original ID; changed
  payloads raise an idempotency conflict. Event receipts also deduplicate redelivery.
- Acceptance atomically creates the notification, one inbox item per unique
  recipient, a request receipt, and an outbox event (plus an event receipt for
  consumer calls). Failures roll back these writes together.
- Each ingestion call commits independently with `REQUIRES_NEW`. An outer
  rollback cannot undo acceptance, and uncommitted outer recipients are invisible.
  Prefer nontransactional transport adapters.
- New requests reject expired notifications. Accepted request/event receipts
  remain replayable at and after expiry without creating additional inbox items.
- Preferences are stored but **not enforced in stage 1**. Even a disabled
  `IN_APP` preference does not suppress inbox creation for an active, authorized
  tenant recipient. Preference filtering and its all-opted-out policy remain
  future work; email/push delivery is also not implemented.

The outbox is persisted, not published. These guarantees do not imply end-to-end
exactly-once delivery. See the [ingestion guide](docs/development/idempotent-ingestion.md)
for authorization requirements, retention, transaction boundaries, and retries.

## Technology and conventions

- JDK 27; no preview features.
- Spring Boot 4.1.2 with Gradle-managed dependencies.
- PostgreSQL with Flyway migrations; restricted runtime database credentials.
- Lombok for getters and constructor boilerplate; validated domain methods.
- `Asia/Phnom_Penh` (UTC+07:00) for the application clock, JSON timestamps,
  JDBC sessions, and logs. Stored timestamps remain absolute instants.

See [build.gradle](build.gradle) and [compose.yaml](compose.yaml) for the
configured versions rather than treating this README as a dependency lockfile.

## Verify the project

With JDK 27 and Docker Desktop running Linux containers, execute from the repository root:

```powershell
.\gradlew.bat test bootJar
```

Integration tests create disposable PostgreSQL databases; they do not use the
persistent Compose database. See [testing](docs/development/testing.md) for
coverage, focused commands, and report locations.

To run the application locally, follow [Getting started](docs/getting-started.md).
Database initialization alone is insufficient: apply migrations first.

## Repository layout

```text
docs/                   Architecture, development, and operations documentation
src/main/java/          Application and domain code
src/main/resources/     Application configuration and Flyway migrations
src/test/java/          Unit and PostgreSQL integration tests
docker/postgres/init/   Database role and schema initialization
scripts/                Local setup utilities
compose.yaml            Persistent local PostgreSQL service
build.gradle            Dependencies, JDK toolchain, and Gradle tasks
```
