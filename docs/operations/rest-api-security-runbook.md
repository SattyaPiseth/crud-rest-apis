# REST API security deployment runbook

[Documentation index](../README.md) · [REST API contract](../development/rest-api.md) ·
[Database operations](database.md)

This runbook is a deployment handoff for a controlled production rollout. The
repository contains no production API ingress, application deployment manifest,
or load balancer configuration. Nothing here proves that external controls have
been installed or that production has been deployed.

## Prerequisites and ownership

- The deployment operator owns TLS termination, ingress routing, request limits,
  rate limiting, trusted proxy configuration, and external monitoring.
- Publish the API only behind the approved TLS ingress. Restrict direct access
  to the application port so clients cannot bypass the ingress.
- Supply `APP_API_ENABLED=true`, the exact trusted `APP_API_ISSUER`, an HTTPS
  `APP_API_JWK_SET_URI`, and `APP_API_AUDIENCE` from the deployment secret/config
  system. JWK retrieval uses the JVM's standard TLS trust/hostname verification,
  rejects redirects, and bypasses ambient JVM proxy settings; permit direct
  outbound HTTPS to the trusted JWK endpoint.
- If Google is the upstream login provider, configure an identity broker to
  issue the API tokens. Do not configure this API to accept Google ID tokens
  directly: they do not carry the required tenant, producer, or API permission
  claims. The Google OAuth Client ID is not the API audience. Follow the
  [identity broker guide](../development/identity-broker.md) and verify a
  broker-issued signed access token before setting `APP_API_ENABLED=true`.
- Supply runtime datasource credentials for `crud_app`. Keep migration
  credentials separate. The migration login must be `NOINHERIT`, non-superuser,
  non-owner, `NOBYPASSRLS`, and granted `crud_owner` membership for explicit
  `SET ROLE`; never mount it into the application container.
- Prepare an encrypted, restorable database backup and agree on a maintenance
  window. The RLS migration locks tenant tables; zero-downtime lock behavior
  has not been measured.
- Obtain operations approval for all provisional rate limits after staging load
  tests. Capacity, burst, and NAT-sharing behavior are not known from this repo.

## External ingress handoff

For NGINX, merge the insertion example in
[the REST API guide](../development/rest-api.md#request-size-and-rate-limiting)
into the existing TLS virtual host. Do not copy its sample upstream address.
The example's 5 requests/second per-IP edge limit and burst of 10 are provisional
and apply to all API requests; they are not authenticated per-tenant quotas.

Configure a shared limiter in the JWT-validating gateway or application for the
provisional authenticated classes: reads 20 requests/second and writes 5
requests/second per verified `(tenant_id, sub)`. Use shared state across ingress
replicas. Do not derive identity from a client-supplied header. If NGINX is behind
a load balancer, configure real-IP handling only with actual trusted proxy CIDRs
and ensure the ingress cannot be reached through an untrusted path.

Set network request-body enforcement to 256 KiB (`client_max_body_size 256k;`
for NGINX). Verify proxy buffering and chunked-request behavior for the selected
version. The application filter is a second layer and returns 413 after auth
without trusting `Content-Length`. The gateway must return 429 for rate rejection;
validate `Retry-After` behavior if supported and monitoring at that boundary. Health probes
use `/actuator/health/liveness` and `/actuator/health/readiness`; these expose
status only, not component details.

## Controlled deployment sequence

1. Capture the release SHA, deployment configuration, current Flyway history,
   database backup identifier, and ingress configuration version.
2. Verify role attributes and memberships using an administrative connection:

   ```sql
   SELECT rolname, rolsuper, rolinherit, rolcreatedb, rolcreaterole, rolbypassrls
   FROM pg_roles WHERE rolname IN ('crud_app', 'crud_owner', '<migration-login>');
   SELECT pg_has_role('<migration-login>', 'crud_owner', 'MEMBER');
   ```

   Confirm `crud_app` is not a member of `crud_owner`, is not superuser or
   `BYPASSRLS`, and has no schema `CREATE` privilege. Confirm the migration login
   is `NOINHERIT` and can explicitly `SET ROLE crud_owner`.
3. Roll out the context-aware application while RLS is still disabled. Verify
   all replicas are on that release and drain every pre-context instance.
4. Run Flyway with the separate migration credentials injected by the approved
   secret manager:

   ```powershell
   .\gradlew.bat migrateDatabase
   ```

   Linux deployment agents may use `./gradlew --no-daemon migrateDatabase`.
   Do not put credentials in command-line arguments, logs, or repository files.
   The sequence applies V1/V2 for fresh databases, V3 for RLS, and V4 to remove
   runtime access to `app.flyway_schema_history`.
5. Before reopening normal traffic, verify migration history with the migration
   login, not `crud_app`:

   ```sql
   SELECT version, success FROM app.flyway_schema_history ORDER BY installed_rank;
   SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, p.cmd,
          p.qual, p.with_check
   FROM pg_class c
   JOIN pg_namespace n ON n.oid = c.relnamespace
   LEFT JOIN pg_policies p ON p.schemaname = n.nspname AND p.tablename = c.relname
   WHERE n.nspname = 'app'
   ORDER BY c.relname;
   ```

   Expect successful versions 1 through 4 and one `ALL` policy with both tenant
   predicates on each of the eight tenant-owned tables. Verify runtime grants
   do not include DDL, `TRUNCATE`, `BYPASSRLS`, owner membership, or access to
   Flyway history.
6. Apply the ingress body and rate controls. Run `nginx -t` and reload only if
   the configuration test succeeds (`nginx -t && nginx -s reload`). Test through the public staging listener:
   a valid authenticated read/write, invalid token (401), insufficient scope
   (403), body over 256 KiB (413), and a bounded burst exceeding the configured
   limit (429). Confirm traffic succeeds again after the bucket recovers. Never
   run burst tests against production without explicit approval.
7. Check liveness/readiness status, one successful signed JWT request, one
   invalid signature, cross-tenant read/write denial, and database connection
   health. Re-run the repository tests against disposable PostgreSQL before
   promotion:

   ```powershell
   .\gradlew.bat test --console=plain --max-workers=1
   .\gradlew.bat jacocoTestReport
   git diff --check
   ```

## Monitoring and rollback

Monitor ingress counts for 401/403/413/429 and 5xx, rate-limit rejection
counts, request latency, JWK retrieval failures, application readiness, database
pool saturation, locks, query latency, and PostgreSQL errors. Alert on repeated
JWK failures, readiness loss, unexpected 5xx spikes, and unusual 413/429 rates.
Do not log JWTs, request bodies, SQL bind values, or tenant row contents.

If application validation fails before V3, roll back the application release
while RLS remains disabled. After V3, do not roll back to code that omits tenant
context. Prefer rolling forward a context-aware application fix. A return to
pre-context code requires draining traffic and a separately approved
schema-owner procedure to disable RLS and drop policies; V4's revocation on
Flyway history remains in force. There is no automatic Flyway downgrade.

If a migration fails, stop promotion, keep traffic closed or on the compatible
pre-migration release, inspect Flyway and PostgreSQL logs through the restricted
administrative channel, and confirm transactional rollback. Do not edit applied
migrations or manually delete Flyway history. Restore from the verified backup
only under the database recovery procedure. Re-enable normal traffic after
schema, role, signed-token, tenant-isolation, ingress-413, and rate-limit-429
checks pass.

## Current evidence and blockers

- On 2026-11-18, local Google → Keycloak → Postman validation produced an
  access token with issuer `http://localhost:8180/realms/crud-api`, audience
  `notification-api`, the approved tenant UUID, `inbox:read`, `inbox:write`,
  and no `producer_id`. An active PostgreSQL recipient matches the exact tenant,
  issuer and Keycloak subject. The local Spring Boot API started successfully
  and returned HTTP 401 with a bearer challenge for an unauthenticated inbox
  request. This is development evidence only; no production listener or
  deployment is enabled.
- Repository tests exercise PostgreSQL 18 migrations and RLS under `crud_app`,
  including migration failure rollback, cross-tenant reads/writes, pool reuse,
  concurrency, and absence of runtime access to Flyway metadata.
- Application tests cover JWT signatures/claims/scopes, request-size boundaries,
  and public status-only health probes.
- No production ingress, shared rate limiter, deployment workflow, backup
  environment, or production workload exists in this repository. NGINX syntax,
  actual network 413/429 behavior, trusted proxy behavior, distributed quota
  behavior, capacity thresholds, and backup recovery remain operator-owned
  staging/production validation items.
