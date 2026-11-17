# Documentation

[Project overview](../README.md)

Documentation describes the implemented repository behavior. Proposed capabilities
are marked as remaining work. Unless stated otherwise, run commands from the
repository root in PowerShell.

## Choose a guide

| Goal | Guide |
|---|---|
| Set up the project or run it for the first time | [Getting started](getting-started.md) |
| Understand packages, OOP, auditing, and timestamps | [Domain model and conventions](architecture/domain-model.md) |
| Understand the schema and UUID references | [ER diagram and relationships](architecture/er-diagram.md) |
| Submit commands or implement a consumer adapter | [Idempotent ingestion](development/idempotent-ingestion.md) |
| Configure JWT authorization and call HTTP endpoints | [REST API](development/rest-api.md) |
| Run tests and inspect failures | [Testing and verification](development/testing.md) |
| Initialize, migrate, maintain, or back up PostgreSQL | [Database operations](operations/database.md) |
| Prepare and verify REST API security deployment | [REST API security runbook](operations/rest-api-security-runbook.md) |

## Suggested reading order

1. Read the [project overview](../README.md) for current scope.
2. Follow [Getting started](getting-started.md) to verify the environment.
3. Study the [domain model](architecture/domain-model.md) and [ER diagram](architecture/er-diagram.md).
4. Follow the [ingestion guide](development/idempotent-ingestion.md) to use the application services.
5. Consult [testing](development/testing.md) and [database operations](operations/database.md) as needed.

## Source references

| Topic | Source |
|---|---|
| Dependencies and JDK version | [build.gradle](../build.gradle) |
| Application configuration | [application.yaml](../src/main/resources/application.yaml) |
| Local database configuration | [application-local.yaml](../src/main/resources/application-local.yaml) |
| HTTP access policy | [SecurityConfiguration.java](../src/main/java/sattya/piseth/crudrestapis/config/SecurityConfiguration.java) |
| Timestamp formatting | [TimeConfiguration.java](../src/main/java/sattya/piseth/crudrestapis/config/TimeConfiguration.java) |
| Ingestion transactions | [NotificationIngestionService.java](../src/main/java/sattya/piseth/crudrestapis/notification/application/NotificationIngestionService.java) |
| Event consumer | [NotificationEventConsumer.java](../src/main/java/sattya/piseth/crudrestapis/notification/application/NotificationEventConsumer.java) |
| Database migrations | [Migration directory](../src/main/resources/db/migration) |

## Documentation conventions

- Keep the root README short and link to detailed guides.
- Put design explanations in `architecture/`, developer workflows in
  `development/`, and database/deployment procedures in `operations/`.
- Use one descriptive H1 per page and sequential H2/H3 sections.
- Use relative links so navigation works in repository viewers and IDEs.
- Label code blocks with their language; identify incomplete examples.
- Update documentation with the corresponding code or migration change.
- Keep credentials out of examples. Describe environment variables by purpose.
- State verification limits; passing integration tests is not a throughput benchmark.

The former root guides have moved:

| Former path | Current path |
|---|---|
| `NOTIFICATION_SERVICE.md` | [architecture/domain-model.md](architecture/domain-model.md) |
| `NOTIFICATION_ERD.md` | [architecture/er-diagram.md](architecture/er-diagram.md) |
| `INGESTION.md` | [development/idempotent-ingestion.md](development/idempotent-ingestion.md) |
| `DATABASE.md` | [operations/database.md](operations/database.md) |

The locally generated `HELP.md` is ignored by Git and is not the project documentation entry point.
