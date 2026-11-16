# PostgreSQL for crud-rest-apis

Verified against official sources on 2026-11-16 and Context7 documentation.

## Version and security baseline

PostgreSQL has no community LTS edition: each major receives five years of
support. PostgreSQL **18.7** is the latest stable minor listed today; version 18
is supported until 2030-11-14. Version 19 is still a release candidate.
See the [official support policy](https://www.postgresql.org/support/versioning/).

The [security matrix](https://www.postgresql.org/support/security/) lists 18.7
as the fix release for the November 2026 server advisories, including
CVE-2026-120151 and CVE-2026-120154. Staying on the latest minor includes earlier
fixes; it is not a guarantee against unknown vulnerabilities.

Spring Boot 4.1.2's [dependency management](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
already selects pgJDBC **42.7.14**. Keep the unversioned PostgreSQL dependency.
The [driver security advisories](https://jdbc.postgresql.org/security/) identify
42.7.14 as fixing CVE-2026-107314 and CVE-2026-107315; it also includes the
42.7.12 channel-binding fix. Review the complete resolved dependency graph and
[Spring advisories](https://spring.io/security/) before production releases.

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

## Start Spring Boot afterward

```powershell
./gradlew.bat bootRun --args='--spring.profiles.active=local'
```

In IntelliJ, select the `local` profile and set the working directory to the
project root. Spring's config tree imports only the application password directory.
For the current context test against the local database:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
./gradlew.bat test
Remove-Item Env:SPRING_PROFILES_ACTIVE
```

Use a dedicated ephemeral PostgreSQL database for CI tests once CRUD tests are
added. Do not run destructive integration tests against persistent development data.

## Application and database practices

- The runtime account has CRUD privileges through defaults for objects created
  by `crud_owner`; it cannot create tables, roles, databases, or bypass RLS.
- Before adding entities, introduce versioned Flyway or Liquibase migrations.
  Run migrations separately using a dedicated migration login permitted to
  `SET ROLE crud_owner`; create objects under that role in the `app` schema.
  Do not give the running application migration credentials. The initial script
  creates roles/schema only and is not a replacement for migrations.
- Hibernate uses `ddl-auto=validate`, Open Session in View is disabled, and SQL
  initialization is disabled. Put transaction boundaries in services; use DTOs
  for responses and parameterized queries. Never interpolate user input into SQL.
- Hikari starts with 2 idle / 10 maximum connections. Budget total connections
  across all app replicas plus migrations and administration below the configured
  database limit of 50. Tune with measurements, not copied production numbers.
- Runtime SQL timeout is 30 seconds, lock timeout 5 seconds, and idle transaction
  timeout 60 seconds. Review these limits for longer workloads. UTC is configured.
- Do not enable SQL parameter logging with sensitive production data. Monitor
  slow queries, pool saturation, disk space, locks and backup completion.

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
