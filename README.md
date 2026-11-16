# Notification Service

A Spring Boot case study implementing a tenant-aware notification domain,
auditing, transactional in-app ingestion, and database-backed deduplication.

The repository and Gradle project retain the name `crud-rest-apis`.

## Start here

- [Getting started](docs/getting-started.md): prerequisites, tests, migrations, and local startup.
- [Documentation index](docs/README.md): find guidance by task.
- [ER diagram](docs/architecture/er-diagram.md): tables, relationships, and constraints.
- [Ingestion guide](docs/development/idempotent-ingestion.md): application-service examples and retry semantics.

## Current implementation

| Implemented | Remaining |
|---|---|
| Six domain entities and two deduplication receipt tables | HTTP notification endpoints and identity-provider integration |
| Tenant-aware foreign keys and ownership-scoped lookup | Kafka listener and outbox publisher |
| Auditing and optimistic locking | Delivery workers, leases, retries, and provider adapters |
| Idempotent ingestion and transport-independent event consumer | Verified destinations, templates, and delivery-attempt history |
| Transactional outbox creation | SSE, production load testing, and retention automation |

All HTTP requests are currently denied by the security configuration. An
application startup does not expose a usable notification API.

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
