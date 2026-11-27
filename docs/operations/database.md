# PostgreSQL setup and operations

[Documentation index](../README.md) · [Getting started](../getting-started.md)

Run all PowerShell commands from the repository root unless a different shell is
explicitly shown. This guide describes the checked-in configuration.

## Configured versions and access model

[compose.yaml](../../compose.yaml) pins `postgres:18.7-trixie`.
[build.gradle](../../build.gradle) declares Spring Boot 4.1.2 and lets its dependency
management select the PostgreSQL driver. These are repository settings, not a
claim that they are the newest or vulnerability-free versions. Review upstream
support and security advisories when upgrading.

The runtime login is `crud_app`; schema ownership belongs to the non-login role
`crud_owner`. A separate migration login must be provisioned before migrations
can run. The initialization script does not create that login.

## Start the database first (PowerShell)

Use Docker Desktop with Linux containers. Run from this project's root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Initialize-DatabaseSecrets.ps1
docker compose config --quiet
docker compose up -d --wait --wait-timeout 120 --pull always
docker compose ps
```

The execution-policy option applies only to that PowerShell process. The script
creates independent random passwords and preserves existing ones. Run it before
Compose: missing secret files can otherwise become directories. Password files
under `.secrets/` are excluded from Git. Local Compose secrets are files, not an
encrypted secret vault; restrict their Windows filesystem permissions and use a
secret manager for production.

Database: `crud_rest_apis`; application user: `crud_app`; endpoint:
`127.0.0.1:5432`. The Compose project appears as `crud-rest-apis` in Docker Desktop.
The app password is in `.secrets/app/spring.datasource.password`.

The health check makes an authenticated TCP query as `crud_app`; readiness
therefore includes completion of database initialization. Persistent data uses a
named volume mounted at `/var/lib/postgresql`, as required by the
[PostgreSQL 18+ official image](https://hub.docker.com/_/postgres).

## Apply migrations before starting the application

The [initialization script](../../docker/postgres/init/10-app.sh) creates roles and
the `app` schema, not application tables.

### Local Compose database

Run the [local migration helper](../../scripts/Migrate-LocalDatabase.ps1):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Migrate-LocalDatabase.ps1
```

It creates a temporary `NOINHERIT` login using the admin secret already mounted in
the local container, runs the existing Flyway task as `crud_owner`, and removes the login
in its cleanup block. The generated password is not written to a file or printed.
The runtime `crud_app` account remains restricted. Repeating the helper validates
the existing migration history and applies only pending migrations.

If the helper process is forcibly terminated, its cleanup may not run; an
administrator should inspect and remove its unused `crud_migrate_...` login.

### Other environments

An administrator must provision a dedicated migration login with database
connection permission and permission to `SET ROLE crud_owner`. Set it
`NOINHERIT`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`, and
`NOBYPASSRLS`; its membership should permit explicit `SET ROLE` but not ambiently
inherit the owner's DDL privileges. Supply credentials through your shell or
secret manager:

| Environment variable | Value |
|---|---|
| `MIGRATION_DB_URL` | JDBC URL of the target database |
| `MIGRATION_DB_USERNAME` | Dedicated migration login |
| `MIGRATION_DB_PASSWORD` | Migration login secret |

The [migration entry point](../../src/main/java/sattya/piseth/crudrestapis/infrastructure/migration/DatabaseMigrator.java)
sets `crud_owner` before applying [versioned migrations](../../src/main/resources/db/migration).
Owner default privileges grant the runtime account access to the resulting tables.

```powershell
.\gradlew.bat migrateDatabase
```

V1 creates the six foundation tables; V2 adds two deduplication receipt tables;
V3 enables forced row-level security on every tenant-owned table. V4 revokes
runtime access to `app.flyway_schema_history`. Flyway is disabled in the running
application and Hibernate validates the schema.
Do not give runtime credentials DDL privileges or enable `ddl-auto=update` to
work around missing migrations. Add new migration versions rather than editing
already-applied SQL files.

## Start Spring Boot

```powershell
./gradlew.bat bootRun --args='--spring.profiles.active=local'
```

In IntelliJ, select the `local` profile and set the working directory to the
project root. Spring's config tree imports only the application password directory.
Notification foundation tests now use a disposable PostgreSQL container instead
of this persistent local database. See [testing and verification](../development/testing.md)
for test instructions. Run tests with Docker Desktop available:

```powershell
./gradlew.bat test
```

Do not run destructive integration tests against persistent development data.

## Application and database practices

- The runtime account has CRUD privileges through defaults for objects created
  by `crud_owner`; it cannot create tables, roles, databases, or bypass RLS.
  Migration V3 adds forced PostgreSQL row-level policies to all eight tenant
  tables. Tenant-scoped application transactions set `app.tenant_id` transaction
  locally through `TenantDatabaseContext`; absent context denies row access.
  Never replace the local transaction setting with a persistent session setting,
  which could leak across pooled connections. The setting is an application
  defense-in-depth boundary, not proof against arbitrary SQL execution as
  `crud_app`, since that role can set its own custom setting.
- Versioned Flyway migrations are implemented. Run them separately using a
  dedicated `NOINHERIT` migration login permitted to `SET ROLE crud_owner`;
  create objects under that role in the `app` schema. V4 removes the runtime
  role's default CRUD grants on Flyway history; verify migration metadata remains
  inaccessible to `crud_app`.
  Do not give the running application migration credentials. The initial script
  creates roles/schema only and is not a replacement for migrations.
- Hibernate uses `ddl-auto=validate`, Open Session in View is disabled, and SQL
  initialization is disabled. Put transaction boundaries in services; use DTOs
  for responses and parameterized queries. Never interpolate user input into SQL.
- Hikari starts with 2 idle / 10 maximum connections. Budget total connections
  across all app replicas plus migrations and administration below the configured
  database limit of 50. Tune with measurements, not copied production numbers.
- Runtime SQL timeout is 30 seconds, lock timeout 5 seconds, and idle transaction
  timeout 60 seconds. Review these limits for longer workloads. The application
  and database sessions use `Asia/Phnom_Penh` (UTC+07:00); `timestamptz` still
  represents an absolute instant. See [auditing and timezones](../architecture/domain-model.md#auditing-and-timezones)
  for JSON formatting.
- Do not enable SQL parameter logging with sensitive production data. Monitor
  slow queries, pool saturation, disk space, locks and backup completion.

### Rolling deployment and rollback for V3/V4

V3 is intentionally fail-closed for old application versions that do not set the
transaction-local tenant context. First deploy the context-aware application
everywhere while RLS is still disabled, drain pre-context instances, and verify
traffic. Then apply V3/V4 using the migration role in a controlled maintenance
window: V3 changes policy state on all tenant tables and V4 restricts migration
metadata. Confirm signed-token API traffic and the PostgreSQL RLS integration
suite after migration. Do not reintroduce a pre-context application while V3 is
active.

To roll back to application code without context support, drain application
traffic and have the schema owner execute an approved rollback that drops
`app_tenant_isolation` from each tenant table and disables RLS. This is an
operator-coordinated emergency action, not an automatic Flyway downgrade.
Restore the context-aware application before re-enabling RLS. Keep a database
backup and rehearse this sequence on a production-like copy before rollout.
Do not restore runtime access to Flyway metadata during rollback. Recover failed
migrations from a consistent database backup or repair the migration forward;
do not manually delete Flyway history or edit applied migration files.

RLS predicates add a transaction-scoped context query and are evaluated by
PostgreSQL for tenant rows. No production workload benchmark is available here;
measure query latency and plans before selecting production pool sizing.

## Production differences

The `local` profile explicitly disables TLS for this loopback-only development
setup. Do not enable that profile in production. Supply
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD` through deployment configuration/secrets. Example URL:

```text
jdbc:postgresql://db.example.internal:5432/crud_rest_apis?sslmode=verify-full&sslrootcert=/run/secrets/postgres-ca.crt&requireAuth=scram-sha-256&channelBinding=require
```

Provision trusted server certificates, enable PostgreSQL TLS, and require
`hostssl` SCRAM rules in `pg_hba.conf` for approved networks, rejecting non-TLS
connections. The URL alone does not enable server TLS. Use private networking,
encrypted disks/backups, secret rotation, and a tested recovery process. A single
Docker Desktop instance is a development setup, not an HA production deployment.
See [pgJDBC TLS guidance](https://jdbc.postgresql.org/documentation/ssl/).

## Maintenance and recovery

`docker compose stop` stops the server. `docker compose down` preserves the named
volume. **Do not use `down -v` unless you intend to delete the database.**
Initialization scripts and password settings run only on a fresh data directory;
editing secret files does not rotate existing database passwords. Rotate the SQL
role password and its secret together, then restart clients.

For an interactive administrative session (password is loaded inside container):

```powershell
docker compose exec postgres bash
```

Inside that shell:

```bash
export PGPASSWORD="$(cat /run/secrets/postgres_password)"
psql -U postgres -d crud_rest_apis
# After leaving psql, create a custom-format backup:
pg_dump -U postgres -d crud_rest_apis -Fc -f /tmp/crud-rest-apis.dump
exit
```

Then copy the backup out without PowerShell binary redirection:

```powershell
New-Item -ItemType Directory -Force backups
docker compose cp postgres:/tmp/crud-rest-apis.dump ./backups/crud-rest-apis.dump
```

Secure and copy backups off the workstation. Restore into an isolated database
using `pg_restore`, provision roles first, and verify data/application behavior.
A volume is not a backup; production should also plan WAL archiving/PITR.

Before each deployment, pull and scan the exact image and scan Java dependencies:

```powershell
docker compose pull
docker scout cves postgres:18.7-trixie --exit-code
./gradlew.bat dependencyInsight --dependency postgresql --configuration runtimeClasspath
```

Review scan findings, including OS packages, and remediate applicable CVEs before
production. Use the approved image digest for reproducible production deployments;
refresh it when patched images are released. For a minor update, back up, review
release notes, change the pinned minor tag, pull and recreate, then verify.
For a major update, use `pg_upgrade` or dump/restore into a new volume; never
simply change the major tag against the existing data directory.

## V5 outbox lifecycle upgrade

V5 extends outbox_event with explicit publication state, claims, leases, attempts,
and retry timing. It preserves forced RLS and existing records. Apply it using the
separate migration login; worker credentials are provisioned separately and the
worker remains disabled. See [outbox operations](outbox-publication.md) for the
backfill policy, compatibility window, least-privilege grants, and recovery limits.
No persistent V5 application is implied by disposable integration-test results.
