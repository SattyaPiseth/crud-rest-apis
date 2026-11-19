# Identity broker for Google sign-in

[Documentation index](../README.md) · [REST API contract](rest-api.md) ·
[Security deployment runbook](../operations/rest-api-security-runbook.md)

The REST API is a JWT resource server; it does not implement browser login,
OAuth authorization-code handling, token exchange, or token issuance. Google
can authenticate a user, but a standard Google ID token is not an API access
token for this service: it lacks the application-specific tenant, producer,
and permission claims. Keep the API disabled until an identity broker issues
tokens meeting the contract below and the operational prerequisites are met.

## What the repository provisions

The `identity` Compose profile starts a local development Keycloak server with
persistent storage and an entrypoint guard. It does not import a realm or create
clients, scopes, protocol mappers, Google federation, role assignments, tenant
attributes, or database recipients. A fresh checkout/empty `keycloak_data` volume
requires the manual setup below, including the protected local environment file.

The Postman settings and dated validation results below describe an existing,
manually configured local realm retained in that volume. They are examples and
historical observations, not automatic provisioning or fresh-install guarantees.
Production broker deployment, TLS, secrets, and provisioning remain operational work.

## Recommended trust flow

Use a separately operated OpenID Connect identity broker, such as Keycloak,
with Google configured as an upstream identity provider:

1. Register the broker's exact Google callback URL as an authorized redirect
   URI on the Google OAuth client. Configure the Google Client ID and its
   client secret in the broker's protected configuration. The client secret
   must never be sent to this API, a browser, or committed to the repository.
2. Create a non-login OIDC resource client with client ID `notification-api`,
   disable its login/grant flows and service accounts, and create the
   `notification-api-access` client scope. The scope must add `notification-api`
   to access-token `aud`, and map administrator-managed `tenant_id` and
   `producer_id` user attributes into access-token claims only. These roles and scope
   were manually configured in the existing local realm; create them on a fresh installation.
3. Configure the API to trust the broker realm's exact `iss`, its HTTPS JWKS
   endpoint, and `notification-api` as `APP_API_AUDIENCE`. Do not use the
   upstream Google Client ID as the API audience. `APP_API_ROLE_SOURCE_CLIENT`
   defaults to `notification-api`; set it explicitly if the resource client has
   another ID. Audience validation and role extraction are independent.
4. Provision tenant membership in administrator-controlled broker data.
   Disable self-service edits to authorization attributes. Issue a canonical
   UUID `tenant_id`. Do not infer tenant membership from an email address,
   email domain, or user-editable profile field.
5. Provision producer identities separately. Only authorized producer
   principals should receive a nonblank `producer_id` claim. OAuth `client_id`
   identifies an OAuth client; it is not a substitute for this business
   producer identity.
6. Create the API client roles `notifications:write`, `inbox:read`, and
   `inbox:write`. Assign those roles only to authorized users/groups through
   administrator-controlled role mappings. Register the actual OAuth login
   client separately, attach `notification-api-access` as a default client
   scope, and ensure the API roles are allowed by its role-scope mappings.
   Keep the standard `roles` mapper enabled so assigned client roles appear
   under `resource_access.notification-api.roles`. Never grant API access
   merely because a caller requests an OAuth scope.
7. Provision recipient rows using the broker token's exact issuer and stable
   subject. Inbox ownership is looked up by `(tenant_id, iss, sub)`, so the
   Google upstream subject is not the subject to insert in the application
   database.
8. Test real broker-issued tokens in staging: correct issuer/audience/signature,
   missing and cross-tenant claims, each required scope, unregistered producer,
   disabled recipient, and expiry. Keep `APP_API_ENABLED` false until these
   checks and the ingress/database runbook are complete.

Keycloak API client roles appear in the signed access token under
`resource_access.notification-api.roles`; the application maps these roles to
its route authorities. It also accepts `scope` and `scp` for other providers.
Grant API roles independently and only to principals that need them. Read and
write permissions are separate. Verify claims in the broker-issued access
token without logging or sharing the token itself.

### Postman local test client

The existing manually configured local realm has a public OIDC client named `notification-postman` with
Standard Flow enabled, implicit/direct grants/service accounts disabled, the
exact redirect `https://oauth.pstmn.io/v1/callback`, PKCE challenge method
`S256`, and `notification-api-access` attached as a default scope. The API
audience is also configured as a client-level access-token mapper on
`notification-postman`, so it is included even when no API roles have been
assigned. The scope retains its audience mapper for other clients that use it.
Use Postman's **Authorization Code (With PKCE)** grant:

| Postman field | Value |
|---|---|
| Grant Type | Authorization Code (With PKCE) |
| Callback URL | `https://oauth.pstmn.io/v1/callback` |
| Auth URL | `http://localhost:8180/realms/crud-api/protocol/openid-connect/auth` |
| Access Token URL | `http://localhost:8180/realms/crud-api/protocol/openid-connect/token` |
| Client ID | `notification-postman` |
| Client Secret | Leave blank |
| Code Challenge Method | `S256` |
| Scope | `openid profile email notification-api-access` |

Use the **access token** for API requests, not the ID token. Do not copy tokens
into tickets, logs, or source files. This obtains a token for Google-authenticated
users, but a request will still fail until an administrator assigns an approved
tenant UUID, eligible API client roles, and the matching active recipient row.
Do not set a tenant value based on guesswork. Keep the API disabled until the
claims have been inspected locally and recipient provisioning is verified.

### Dedicated local producer client

The retained local `crud-api` realm has a separate confidential service-account
client named `notification-e2e-producer` for development-only ingestion. It uses
Client Credentials; Standard Flow, implicit flow, direct grants, and full-scope
role assignment are disabled. Its service account is assigned and scope-limited
to only the `notification-api` client role `notifications:write`. Client-level
access-token mappers add the `notification-api` audience and fixed
`tenant_id`/`producer_id` values. The client does not inherit the recipient
Postman scope or inbox roles.

The client secret and the non-secret local test identifiers are stored in the
Git-ignored `.secrets/notification-e2e-producer.env`. Keep that file local; do
not paste its contents into source, tickets, or chat. Only trusted Keycloak
administrators can change the fixed claim mappers or service-account role
assignment. This local principal is not a production producer registry or
revocation mechanism.

### Local validation status

As verified on 2026-11-19, the active recipient account is matched to its
PostgreSQL row by `(tenant_id, issuer, subject)`. Its effective API roles and
the `notification-api-access` scope contain only `inbox:read` and `inbox:write`,
not `notifications:write`. Identifiers are intentionally omitted.

The dedicated producer service-account token was locally decoded without
printing or saving the token. Its issuer, `notification-api` audience,
tenant claim, fixed producer identity, expiry, and sole API role
`notifications:write` matched the expected contract. The generated confidential
client secret is saved only in the ignored local secrets file.

The local database initially had only Flyway V1 and V2 applied. Before
considering the lifecycle test complete, the API was stopped and the documented
local migration helper applied V3 and V4. Verification then confirmed all
eight tenant tables have forced RLS and tenant policies, unscoped and foreign
tenant-context reads return no rows, a cross-tenant write is denied, and the
runtime role cannot read Flyway history. One development-only notification was
created before RLS was enabled; a separate notification was created after the
migrations. Both are local test data.

With the migrated database and loopback-only API, producer ingestion returned
201; same-key/same-payload replay returned 200 with the same notification ID;
changed payload returned 409; an unknown recipient returned 400; and the
producer's inbox request returned 403. Temporary negative clients verified
that a `client_id` claim cannot replace `producer_id`, inbox-only roles cannot
ingest, and wrong issuer/audience tokens return 401. Those temporary clients
were removed. The local database currently has only one provisioned tenant, so
a live request between two existing tenants could not be exercised; the
two-tenant application/RLS regression tests cover that case.

The recipient then used a fresh Postman token against the post-migration test
item. The user reported HTTP 200 for inbox list, item detail, read, and archive,
and HTTP 403 when attempting ingestion with the recipient token. A read-only
database check after those calls confirmed both read and archive timestamps,
version 2, and the same-tenant notification association. No token or credential
was shared. The reported detail GET was before mutation; the user did not report
a post-archive list/detail check. The API integration test
`NotificationApiTests.readArchiveVersionAndPaginationContract` verifies that an
archived item is omitted from the inbox list and remains retrievable by ID.

The API and development broker are test-only, loopback-bound local services;
this is not production readiness.

## Start the local Keycloak service

The repository's `identity` Compose profile runs Keycloak 26.8.2 in development
mode, bound only to `127.0.0.1:8180`. This is for local development only; do not
expose this development-mode instance to a network or use it for production.
The image is pinned by digest for reproducibility, and realm data persists in
the ignored `keycloak_data` Docker volume.

### Java4Shell / CVE-2026-114938 status

The local image currently reports Red Hat OpenJDK `21.0.13+9-LTS`. The
container-visible CPU flags do not include `avx512vbmi`, and the published
Keycloak port is bound to loopback. Red Hat's advisory says x86-64 systems
without AVX-512 VBMI are not currently known to be affected. These facts reduce
the current local exposure; they do not prove the JVM build is patched or
justify using this development configuration on a different host.

The Compose service now runs `docker/keycloak/start-guard.sh` before Keycloak.
It refuses to start if `/proc/cpuinfo` is unreadable, lacks recognizable x86
flags, cannot be inspected, or exposes `avx512vbmi` (also accepting the
alternative `avx512_vbmi` spelling). This is a local fail-closed safeguard, not a CVE fix:
container CPU flags may be masked by a hypervisor, so operators must check the
physical host too. Do not remove the guard to force deployment on an affected
host.

As checked on 2026-11-18, the OpenJDK 2026-11-17 advisory lists upstream
21.0.13.0.3 and earlier as affected and recommends upgrading. Red Hat's
CVE-2026-114938 notice says its OpenJDK 21 builds and bundled runtimes are still
under investigation and does not identify a confirmed fixed Red Hat build.
The current upstream Keycloak release remains 26.8.2, so there is no newer
official Keycloak release to move to as a verified CVE fix. The image digest
pin only makes the assessed image reproducible; it is **not a security patch**.

Do not deploy the current image to a physical server that exposes AVX-512 VBMI.
Do not add an unverified JVM flag or replace Keycloak's bundled runtime with an
unsupported custom JDK as a claimed fix. Keep the local listener loopback-only,
do not use this instance for production, monitor the linked vendor advisories,
and update to a Keycloak image whose bundled OpenJDK is explicitly confirmed
fixed by Red Hat/Keycloak as soon as one is published. Recheck the image's Java
vendor/version and CPU exposure after the update.

Create a local administrator password file in the already-ignored `.secrets`
directory. Keep its value private and do not commit it:

```powershell
New-Item -ItemType Directory -Force .secrets | Out-Null
$bytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$password = [Convert]::ToBase64String($bytes)
@"
KC_BOOTSTRAP_ADMIN_USERNAME=kcadmin
KC_BOOTSTRAP_ADMIN_PASSWORD=$password
"@ | Set-Content -NoNewline -Encoding ascii .secrets/keycloak.env
Remove-Variable password, bytes
docker compose --profile identity up -d keycloak
```

Open `http://localhost:8180/admin/` and sign in as `kcadmin` using the password
in the local file. In the Admin Console:

1. Select the existing realm named `crud-api` (created for this local
   environment); on a fresh Keycloak volume, create it first.
2. Add Google under **Identity providers** using the Google Client ID already
   provided and the matching client secret from the approved local secret
   manager. Do not send the secret to the API or check it into Git. Register
   Keycloak's displayed callback URL as the OAuth authorized redirect URI in
   Google Cloud Console. It is expected to use
   `http://localhost:8180/realms/crud-api/broker/google/endpoint` for this
   local realm and port; use the exact URI shown by Keycloak.
3. Configure an OIDC client for the application that obtains access tokens and
   an API audience/resource identifier `notification-api`. Ensure the broker's
   **access token**, not merely its ID token, has `notification-api` in `aud`.
4. Configure administrator-controlled user attributes/roles and protocol
   mappers for `tenant_id`, `producer_id` (only for registered producers), and
   the required scopes. Follow the claim and authorization rules above; do not
   map untrusted profile fields into these claims.
5. Provision the local recipient row against the realm issuer and the broker's
   stable subject. The realm issuer is
   `http://localhost:8180/realms/crud-api`; do not use Google's issuer or
   upstream subject for inbox membership.

Keycloak realm setup is intentionally not imported automatically: Google
client secrets and tenant/producer authorization assignments are operator
configuration and must not be guessed. This local development-mode broker uses
HTTP only on loopback. A deployed broker and the API's JWKS URL must use HTTPS.
Stop it with `docker compose --profile identity stop keycloak`; preserve the
volume if you want to keep the realm.

## Configuration boundary

These are deployment settings, not repository defaults:

| Variable | Value source |
|---|---|
| `APP_API_ENABLED` | Set to `true` only after readiness checks |
| `APP_API_ISSUER` | Exact `iss` from the broker's discovery metadata |
| `APP_API_JWK_SET_URI` | Broker-published HTTPS JWKS URI |
| `APP_API_AUDIENCE` | API resource identifier placed in the token's `aud` |

For the local realm above, after Google federation and claim provisioning are
complete, the corresponding values are:

```text
APP_API_ISSUER=http://localhost:8180/realms/crud-api
APP_API_JWK_SET_URI=http://localhost:8180/realms/crud-api/protocol/openid-connect/certs
APP_API_AUDIENCE=notification-api
APP_API_ROLE_SOURCE_CLIENT=notification-api
```

These values alone do not enable a usable API. The API remains disabled until
the broker actually issues and verifies tokens with the required claims, the
database recipient is provisioned, and local migrations have been applied.

The Google OAuth Client ID and secret are configured only at the broker.
Neither is used as the broker's issuer or JWKS URI. The repository provides the
local development Compose service described above, but no production broker
deployment, Google client secret, automated realm/tenant provisioning, producer
registry, or production ingress. The dated local validation records apply only
to that manually configured environment; repeat token and API checks after
provisioning a fresh environment before enabling its API.

## References

- [Google OpenID Connect documentation](https://developers.google.com/identity/openid-connect/openid-connect)
- [Google OpenID Connect discovery metadata](https://accounts.google.com/.well-known/openid-configuration)
- [Keycloak Server Administration Guide](https://www.keycloak.org/docs/latest/server_admin/) (identity brokering, clients, and protocol mappers)
- [OpenJDK Vulnerability Advisory, 2026-11-17](https://openjdk.org/groups/vulnerability/advisories/2026-11-17)
- [Red Hat CVE-2026-114938 status and mitigation](https://access.redhat.com/security/cve/CVE-2026-114938)
- [Keycloak 26.8.2 container image](https://quay.io/repository/keycloak/keycloak?tag=26.8.2)
- [Spring Security OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
