# Getting started

[Documentation index](README.md) · [Project overview](../README.md)

This guide gets the current implementation building and running locally. It does
not configure a public notification API or a Kafka broker.

## Prerequisites

| Requirement | Purpose |
|---|---|
| JDK 27 | Compile and run the configured toolchain |
| Docker Desktop with Linux containers | Disposable test databases and local PostgreSQL |
| PowerShell | Commands shown in these guides |
| Dependency/image registry access on first run | Gradle dependencies and container images |

Use the checked-in Gradle wrapper. Set IntelliJ's project SDK and Gradle JVM to
JDK 27. All commands below run from the repository root.

## 1. Verify the code

Start Docker Desktop, then run:

```powershell
.\gradlew.bat test bootJar
```

The tests create disposable PostgreSQL containers and apply migrations themselves.
They do not require local database secrets or a running Compose database.

See [testing and verification](development/testing.md) for test selection and reports.

## 2. Initialize local PostgreSQL

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Initialize-DatabaseSecrets.ps1
docker compose config --quiet
docker compose up -d --wait --wait-timeout 120 --pull always
docker compose ps
```

The script preserves existing secrets. Compose initializes the `crud_owner` role,
`crud_app` runtime login, and `app` schema on a fresh data directory. Application
tables are created by Flyway in the next step.

See [database operations](operations/database.md) for credentials, storage,
maintenance, and production differences.

## 3. Apply the schema

For the local Compose database, run the migration helper:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Migrate-LocalDatabase.ps1
```

The helper uses the existing local admin secret inside the container to create
a temporary non-inheriting migration login. It applies V1 through V4 as needed, removes the login,
and restores any existing migration environment variables. It does not change
the runtime account's privileges or start the web application.

For other environments, provision a dedicated migration login and supply
`MIGRATION_DB_URL`, `MIGRATION_DB_USERNAME`, and `MIGRATION_DB_PASSWORD` to
`.\gradlew.bat migrateDatabase`.
See [migration setup](operations/database.md#apply-migrations-before-starting-the-application)
for details.

## 4. Start the application

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

The local profile imports the application password from `.secrets/app/`.
Keep the working directory set to the repository root in IntelliJ.

Expected behavior:

- Hibernate validates the migrated schema.
- The application uses `Asia/Phnom_Penh` for display and session timezones.
- HTTP requests are denied unless the REST API is explicitly enabled and configured.
- No Kafka listener, email/push sender, or SSE endpoint starts.

Use the [ingestion application services](development/idempotent-ingestion.md)
from trusted application code or the tests. To enable HTTP ingestion and inbox
operations, follow the [REST API guide](development/rest-api.md).

## Troubleshooting

| Symptom | Check |
|---|---|
| JDK/toolchain error | Select JDK 27 for the shell and IDE Gradle JVM |
| Testcontainers cannot connect | Start Docker Desktop with Linux containers |
| Database connection refused | Check Compose health and the configured loopback endpoint |
| Missing table/schema validation failure (for example, `delivery`) | Run the local migration helper before starting the application |
| Migration permission denied | Confirm migration login membership and ability to set `crud_owner` |
| Missing secret/config tree | Run the initialization script and use the repository root as the working directory |
| HTTP access denied | Check API enablement, trusted JWT configuration, token scopes, and active recipient membership |
| Audited write has no actor | Supply authenticated context or an explicit trusted background actor |

Do not change Hibernate to `ddl-auto=update` or weaken HTTP authorization to bypass
these setup requirements.
