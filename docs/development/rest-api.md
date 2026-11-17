# Notification REST API (v1)

[Documentation index](../README.md) | [Ingestion semantics](idempotent-ingestion.md)

The API creates immutable notifications and manages each recipient's inbox state.
It does not expose database entities, receipt/outbox administration, arbitrary
notification edits/deletes, recipient provisioning, preference filtering, or
external delivery. Archive is a soft inbox operation; it does not delete receipts.

## Enable and configure

HTTP is denied by default. After database setup and migrations, configure:

| Environment variable | Required value |
|---|---|
| `APP_API_ENABLED` | `true` to enable the API |
| `APP_API_ISSUER` | Exact trusted JWT issuer, maximum 300 characters |
| `APP_API_JWK_SET_URI` | Trusted issuer's absolute HTTPS JWK endpoint; HTTP loopback only for local tests |
| `APP_API_AUDIENCE` | Audience identifying this notification API |

Then run `./gradlew.bat bootRun --args='--spring.profiles.active=local'`.
The enabled API fails startup if required settings are absent/blank. Invalid
`APP_API_ENABLED` values also fail startup rather than falling through to default security. The local
profile supplies database credentials only; it does not provide an identity provider.
JWK keys are fetched by Spring Security's Nimbus decoder. RS256 is the only
accepted JWS algorithm. HTTPS is required for all remote JWK endpoints; HTTP is
allowed only for the exact hosts `127.0.0.1`, `[::1]`, and `localhost`.
JWK HTTP redirects are not followed, and redirect responses are rejected.
Invalid/malformed URLs, credentials, fragments and unsupported schemes fail startup
without echoing the configured URI. Use TLS for the API and JWK endpoint in deployment. No login or token minting
endpoint is included. Obtain access tokens from your trusted identity provider.
The JWK HTTP client uses the JDK's normal TLS trust and hostname verification,
does not follow redirects, and explicitly bypasses ambient JVM proxy settings.
Remote JWK endpoints therefore require direct outbound connectivity. The explicit
`localhost` HTTP exception depends on the host's trusted name resolution; do not
override localhost resolution in production.

Requests use `Authorization: Bearer <access-token>`. Signature, issuer, audience,
expiry and not-before validity are checked with Spring Security's default clock
skew. `exp` is required explicitly. Cookies and HTTP sessions do not authenticate
this API. Spring Security's CSRF protection remains enabled for unsafe requests
without a bearer Authorization header; explicitly supplied bearer-token requests
are exempt because browsers do not attach that credential ambiently. CORS is not
enabled.
All application paths and methods outside the endpoint table remain denied.
Actuator health, liveness, and readiness probes are public but expose status only;
`/actuator/info` remains denied.

## Identity and authorization contract

Only RS256 signatures are accepted; `alg=none`, HMAC, other RSA algorithms and
EC algorithms are rejected. Tokens are signature-verified against the configured
JWK set before their claims are used. The configured issuer and API audience are
required; `exp` and `nbf` are checked by Spring's default validators and clock skew.

| Claim | Meaning |
|---|---|
| `iss` | Must match the configured issuer |
| `aud` | Must contain the configured API audience |
| `exp` | Required token expiry |
| `sub` | Nonblank authenticated subject, at most 180 characters |
| `tenant_id` | Canonical lowercase UUID of the authorized tenant |
| `client_id` | Required for ingestion: nonblank registered producer ID, at most 100 characters |
| `scope` / `scp` | Scopes mapped by Spring Security to `SCOPE_` authorities |

The identity provider must issue tenant and producer claims only after checking
membership and client registration. These claims must not be user-editable profile
attributes. A token authorizes exactly one tenant; request bodies and headers do
not select another tenant or override producer identity. Issuer and client-ID
assignments must remain stable for retained idempotency keys.

Inbox requests additionally resolve an **active** recipient by `(tenant_id, iss,
sub)` in the database. Missing/inactive membership returns 403. A foreign or
missing inbox ID returns the same 404 to an otherwise authorized recipient.
No recipient ID is accepted from the client for inbox ownership.
Membership is authorized at the database check within each request. Deactivation
prevents requests whose membership check occurs after it commits; it does not
cancel an already-authorized in-flight request. This differs from ingestion's
recipient share-lock contract and is covered by a synchronized concurrency test.
Read and write scopes are independent: `inbox:write` permits mutations but does
not grant access to notification content. PUT responses contain state only.
Creation requires producer authorization; the producer need not be an inbox recipient.
Auditing uses the authenticated issuer/subject, never a body-provided actor.

## Endpoints

All request/response bodies are JSON; timestamps identify absolute instants and
are rendered with the application's `+07:00` display offset.

| Method and path | Scope | Success |
|---|---|---|
| `POST /api/v1/notifications` | `notifications:write` | 201 new acceptance; 200 equivalent replay |
| `GET /api/v1/inbox?page=0&size=20` | `inbox:read` | 200 page |
| `GET /api/v1/inbox/{id}` | `inbox:read` | 200 inbox item |
| `PUT /api/v1/inbox/{id}/read` | `inbox:write` | 200 mutation state |
| `PUT /api/v1/inbox/{id}/archive` | `inbox:write` | 200 mutation state |

### Create a notification

Required header: `Idempotency-Key` (nonblank, at most 200 characters).

```json
{
  "type": "ORDER_SHIPPED",
  "category": "ORDERS",
  "title": "Your order shipped",
  "body": "Order 123 is on its way.",
  "expiresAt": "2030-01-01T00:00:00Z",
  "recipientIds": ["00000000-0000-0000-0000-000000000001"]
}
```

`type` and `category` are nonblank strings of at most 100 characters; `title`
is at most 200 and `body` at most 10000. `expiresAt` is optional. Supply 1-1000
nonnull recipient UUIDs before deduplication. Recipients must exist, be active,
and belong to the token tenant. Text whitespace is preserved. Preferences are
still stored-only and do not filter recipients.

```json
{"notificationId":"00000000-0000-0000-0000-000000000002","replayed":false}
```

The notification ID is the ingestion receipt result, not an inbox-item ID. Inbox
IDs are obtained from the inbox endpoints. There is no producer GET-notification
endpoint or Location URL in this version.

A repeated tenant/producer/key with equivalent content returns the original ID
and `replayed: true`; changed content returns 409. Authentication and authorization
are checked on every replay. Existing receipts remain replayable after notification
expiry or recipient deactivation. New expired requests return 400. Acceptance uses
an independent transaction; transport code should remain nontransactional. Retry
unknown outcomes with the **same** key and payload. Receipt retention is indefinite.

PowerShell example (use an actual registered recipient and access token):

```powershell
$headers = @{ Authorization = "Bearer $env:ACCESS_TOKEN"; 'Idempotency-Key' = 'order-123-shipped' }
$payload = @{
  type = 'ORDER_SHIPPED'; category = 'ORDERS'; title = 'Your order shipped'
  body = 'Order 123 is on its way.'; recipientIds = @('00000000-0000-0000-0000-000000000001')
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/notifications' -Headers $headers -ContentType 'application/json' -Body $payload
```

### Read the inbox

The list response is `{ "items": [...], "page": 0, "size": 20, "hasNext": false }`.
`page` is zero-based, 0-10000; `size` is 1-100. Ordering is `createdAt DESC, id DESC`.
Only nonarchived items are listed. Expired notifications remain visible as history;
clients can use `expiresAt` when presenting them. Archived items remain accessible
by ID to their owner. Offset pagination is bounded but not a snapshot: concurrent
insertions can shift page boundaries. A cursor contract can replace it in a future version.

An item contains `id`, `notificationId`, `type`, `category`, `title`, `body`,
`createdAt`, nullable `expiresAt`, nullable `readAt`, nullable `archivedAt`, and
`version`. It excludes tenant IDs, recipient identity, audit actors and receipts.

### Mark read or archive

Send the version from the latest item response:

```json
{"version":0}
```

Version is required and nonnegative. A stale version or concurrent update returns
409; GET the item again before retrying. A successful state change returns the new
version and audited timestamp. Mutation responses contain only `id`, `readAt`,
`archivedAt`, and `version`; read scope is required to retrieve content. Repeating an already-applied operation with the
current version preserves its first timestamp and does not increment the version.
Retrying with an old version returns 409 even if the desired state already exists.
Read and archive are independent operations; archive does not imply read.

## Error contract

Errors use `application/problem+json` with `type`, `title`, `status`, and where
applicable `detail`. Do not parse human-readable detail text as an error code.

| Status | Meaning |
|---|---|
| 400 | Missing/invalid body, header, UUID, pagination or version; invalid recipients or expired new notification; bearer `invalid_request` |
| 401 | Missing/invalid bearer token; standard bearer challenge, with `invalid_token` for invalid credentials |
| 403 | CSRF token required for an unsafe request without bearer credentials; insufficient scope (bearer `insufficient_scope` challenge), invalid producer identity, or no active recipient membership |
| 404 | Inbox item does not exist within the caller's ownership scope |
| 409 | Idempotency conflict, stale version, or optimistic-lock conflict |
| 415 | Unsupported request content type |
| 503 | Recognized transient database/connection failure; `Retry-After: 1` |
| 500 | Permanent or unclassified server failure with sanitized public detail |

Security filters delegate status/challenge construction to Spring's bearer handlers
before adding Problem Detail JSON. Challenge parameters may evolve with Spring.

Database errors are classified through their cause chain: connection-class `08`
SQLSTATEs, serialization/deadlock, lock/query timeout and server-shutdown states
are retryable. Constraint, permission, authentication and schema errors are not.
Native JPA timeouts and translated Spring failures are handled as well. Unknown
transaction-start failures remain 500 rather than being blindly retried.
For 500/503, `errorId` matches `X-Correlation-ID`. Application diagnostics contain
only that ID, exception category and SQLSTATE, not exception messages or row data.
PostgreSQL server-error detail is disabled on application connections; Hibernate
SQL/bind/JDBC-error logging is disabled to prevent bypassing this boundary. Keep
these defaults in deployment, and separately control database-server logs.

A failed acceptance rolls back notifications, inbox rows, receipts and outbox rows.
Validation errors do not leak which foreign tenant owns a requested recipient.
No external delivery or end-to-end exactly-once guarantee is implied.
### Request size and rate limiting

The application rejects API request bodies larger than **256 KiB** with 413
after authentication/authorization and before JSON parsing. The filter reads at
most one byte over the limit, so it applies to chunked bodies and does not trust
`Content-Length`. The cap is above the maximum supported field sizes and 1000
recipient UUIDs; it is a guardrail, not a substitute for ingress buffering limits.
Configure the ingress/API gateway to enforce the same or a smaller limit before
forwarding requests.

This repository contains no deployed API ingress, gateway, or rate-limiting
service. Consequently, request-rate enforcement is **not implemented or
deployment-verified**. Do not rely on this application alone for rate limiting.
The following is an insertion example for an existing NGINX HTTP configuration,
not a complete TLS virtual host or a checked-in deployment. Replace upstream
addresses and route it only from the trusted public listener:

```nginx
# Place limit_req_zone in the http {} block.
limit_req_zone $binary_remote_addr zone=api_ip:10m rate=5r/s;

# Place the following inside the existing API server {}. `notification_api`
# must resolve to a private upstream that is not directly reachable by clients.
client_max_body_size 256k;
limit_req_status 429;
error_page 429 = @api_rate_limited;

location ^~ /api/v1/ {
    limit_req zone=api_ip burst=10 nodelay;
    proxy_set_header Authorization $http_authorization;
    proxy_set_header Host $host;
    proxy_pass http://notification_api;
}

location @api_rate_limited {
    default_type application/problem+json;
    return 429 '{"type":"about:blank","title":"Too Many Requests","status":429}';
}
```

The **provisional** 5 requests/second with a burst of 10 is a per-IP edge
protective limit for all API traffic, not the approved service capacity. NGINX
open-source `limit_req` uses a leaky bucket and maintains state only within one
instance; the example does not identify authentication state or verified tenant
identity. It can throttle unrelated users sharing a NAT address, and each NGINX
replica has an independent bucket. Validate thresholds against measured traffic
and configure a distributed gateway limiter for multi-replica deployments.

The planned authenticated quotas (also provisional) are 20 requests/second for
reads and 5/second for writes, keyed by verified `(tenant_id, sub)`. Basic NGINX
configuration cannot securely derive this identity from a bearer token. Implement
that quota in a JWT-validating API gateway or the application with shared state;
do not trust a client-supplied identity header. Apply the 5/s client-IP limit
before authentication as a separate layer. If NGINX is behind a load balancer,
configure its real-IP module only with the actual trusted proxy CIDRs, prevent
direct untrusted access, and ignore forwarded client-IP headers from other
sources. This app does not process forwarded headers.

At the proxy, `client_max_body_size 256k` rejects larger request bodies with 413;
the application cap remains as defense in depth. NGINX will normally send 413
once it has detected the limit, but whether a client sees the response while
continuing an upload depends on the connection and client behavior. Validate
chunked and known-length requests at the actual ingress. `limit_req_status 429`
sets the rejection status. The example omits `Retry-After` because NGINX does
not compute a per-request retry time. Add that header only if the selected
gateway can provide a meaningful value. Run `nginx -t` before reload, then
exercise below/above-limit traffic and confirm 413, 429, and recovery.
There is no NGINX binary or API ingress in this repository/environment, so these
network-boundary checks remain a deployment blocker.

### PostgreSQL tenant row security

Migration V3 enables and forces PostgreSQL RLS on all eight tenant-owned tables:
`recipient`, `notification`, `notification_recipient`, `delivery`,
`notification_preference`, `outbox_event`, `ingestion_request`, and
`processed_event`. One `FOR ALL` policy applies both `USING` and `WITH CHECK`
against `tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid`.
Missing context yields no visible rows and rejects writes; malformed context
fails the statement. Existing application query predicates and composite tenant
foreign keys remain in place. V4 removes the runtime `crud_app` grant on
`flyway_schema_history`; only the separate migration role needs this metadata.

`TenantDatabaseContext` sets `app.tenant_id` with parameterized `set_config(...,
true)` inside the transaction, using the verified producer tenant for ingestion
or the verified owner tenant for inbox operations. The `true` flag makes the
setting transaction-local; commit/rollback resets it before pooled connection
reuse. Background ingestion uses the trusted `ProducerScope` supplied by its
server-side caller. Provisioning/maintenance transactions must set the tenant
context too, or use the separate privileged migration/administration role.

The runtime `crud_app` role is `NOBYPASSRLS`; policies use `FORCE ROW LEVEL
SECURITY` so table owners are also subject (superusers and `BYPASSRLS` roles
still bypass RLS). RLS protects against accidentally omitted tenant predicates,
but is not an independent authorization boundary against arbitrary SQL execution
as `crud_app`: that role can call `set_config` with another tenant value. Prevent
SQL injection, keep the application role restricted, and treat tenant-context
establishment as a trusted server-side decision. Each transaction pays one
additional context-setting round trip; production query impact has not been
benchmarked.

V3 is fail-closed for old application instances that do not set tenant context.
Deploy the context-setting application version to all instances while RLS is
still disabled, drain older instances, then apply V3 and V4. Use a controlled
maintenance window until lock behavior is validated at production scale.
Rollback to pre-V3 code requires a coordinated database rollback that drops
these policies and disables RLS; otherwise old instances will see empty tenant
tables or fail on writes. Keep V4's Flyway-history privilege restriction in
place. Test this sequence on a production-like copy before rollout.

## Verification and references

See [testing](testing.md#rest-api-integration-tests) for signed-token PostgreSQL tests.
Official references:

- [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security bearer token resolution](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html)
- [Spring MVC error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring Boot health probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)
- [RFC 6750: OAuth 2.0 Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750.html)
- [RFC 8725: JSON Web Token Best Current Practices](https://www.rfc-editor.org/rfc/rfc8725.html)
- [PostgreSQL 18 row security policies](https://www.postgresql.org/docs/18/ddl-rowsecurity.html)
- [NGINX request rate limiting](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)
- [NGINX request body size](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_max_body_size)
