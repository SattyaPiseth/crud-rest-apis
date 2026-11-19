# Idempotent ingestion and consumer deduplication

[Documentation index](../README.md) · [Domain model](../architecture/domain-model.md)

Run all commands from the repository root. Java snippets demonstrate calls from
Spring-managed application code; they are not standalone programs or HTTP APIs.

## Scope

Implemented: bounded in-app ingestion, transactional outbox creation, and a
transport-independent consumer. The [REST adapter](rest-api.md) is opt-in; no
Kafka listener is started.
Email/push delivery and outbox publication remain later steps.

Application types live in `notification/application`; the PostgreSQL adapter
lives in `notification/persistence`. Lombok constructor injection, immutable Java
records, the `DeduplicationStore` interface, and existing encapsulated entities
preserve the project's Java/OOP conventions.

## 1. Apply database migrations

[V2__idempotent_ingestion.sql](../../src/main/resources/db/migration/V2__idempotent_ingestion.sql) adds:

| Table | Unique key |
|---|---|
| ingestion_request | tenant_id, producer_id, idempotency_key |
| processed_event | tenant_id, producer_id, consumer_name, event_id |

Both store a fingerprint, original notification ID, audit metadata, and version.
Both have a composite FK to the same tenant's notification. V1 is unchanged.
The current application also requires V3 tenant row-security policies and V4
migration-history privilege restrictions. Apply all pending migrations; V2 alone
is not the current database baseline.

Supply the dedicated migration credentials described in the [database guide](../operations/database.md),
then run:

```powershell
.\gradlew.bat migrateDatabase
```

Runtime Flyway stays disabled. Tests migrate disposable databases, not persistent
development data.

## 2. Ingest a notification

```java
// Derive these values from authorized caller context, never untrusted body fields.
var source = new ProducerScope(authorizedTenantId, registeredProducerId);
var command = new IngestNotification(
    "ORDER_SHIPPED", "ORDERS", "Your order shipped",
    "Order 123 is on its way.", null, List.of(recipientId)
);
var result = ingestionService.ingest(source, "order-123-shipped", command);
```

HTTP callers require an authenticated audit actor. Trusted background callers
wrap the entire call in `BackgroundActor.callAs(...)`. ProducerScope is a trusted
application contract, not an authorization mechanism. The REST adapter derives
this context from validated JWT claims and requires the producer write scope.

- Same key/equivalent command: original ID, `replayed=true`.
- Same key/different command: `IdempotencyConflictException`, mapped to HTTP 409 by the REST API.
- New key: one notification, one inbox row per unique recipient, one outbox event.
- Missing, inactive or cross-tenant recipients: rejection and full rollback.
- Expired new notification: rejection and full rollback.
- Previously accepted commands remain replayable after expiry or recipient
  deactivation; adapters must still authorize the caller on every attempt.

Fan-out is limited to 1000 input recipient IDs. The outbox payload contains
notification ID, recipient count, and occurrence time. No external-channel
delivery jobs are created in this step.

Preferences remain persistence-only in the current implementation: ingestion does not query
`NotificationPreference`. A disabled matching `IN_APP` preference still results
in an inbox item. Filtering, defaults for missing preferences, and behavior when
all recipients opt out must be defined before preference enforcement is added.
Replay returns the original acceptance result; it does not reevaluate preferences.

## 3. Consume an event

```java
var event = new NotificationRequestedEvent(
    stableBusinessEventId, 1, "order-123-shipped", command
);
var result = notificationEventConsumer.consume(trustedSource, event);
```

The consumer binds the service audit actor around the entire proxied transaction.
It reserves the event ID before invoking the same ingestion path. Redelivery
does not recreate business rows. Different event IDs with the same request key
can have separate processed-event receipts referencing one notification.

Reusing an event ID with a changed command or idempotency key raises a conflict.
Only event schema version 1 is accepted.

A future broker adapter must validate the source's tenant/producer permissions,
deserialize the event, call this consumer, and acknowledge only after successful
return. Exceptions propagate: retry transient failures and route permanent
validation/conflict errors through the broker error policy. Never swallow an
exception and acknowledge the record as successfully processed.

The database commit completes before the call returns. A crash after commit but
before broker acknowledgement produces a harmless redelivery. This is not an
end-to-end exactly-once delivery guarantee; Kafka restart/offset behavior is not
part of this stage's tests.

## Transaction and concurrency contract

Each public ingestion entry owns a fresh READ COMMITTED transaction with a
30-second Spring transaction timeout (`REQUIRES_NEW`). This is an independent
acceptance boundary. It cannot see an outer transaction's uncommitted recipients,
and an outer rollback cannot undo a notification it already accepted. Prefer
nontransactional transport adapters.

Nested transactional callers also hold their outer connection while ingestion
acquires another. Size the pool for that usage or avoid nesting these acceptance
calls. Integration tests cover outer rollback independence and the visibility of
flushed but uncommitted recipients.

```text
BEGIN
  INSERT reservation ... ON CONFLICT DO NOTHING
  if duplicate:
    SELECT fingerprint + original result using a new statement snapshot
    reject mismatch or return replay
  validate and share-lock active recipients
  INSERT notification + inbox entries + outbox
  finalize consumer receipt if applicable
COMMIT
```

Unique constraints coordinate across JVMs. A competing insert waits; after the
first transaction commits, a separate READ COMMITTED SELECT sees its receipt.
If the first transaction rolls back, the waiting insert can acquire the key.

Receipt FKs are DEFERRABLE INITIALLY DEFERRED so reservation can precede business
inserts, but cannot commit without a valid same-tenant notification. The unique
constraints remain immediate. Any failure rolls back receipts and business rows
together. Lock timeouts/connection errors propagate for caller retry.

Native SQL is deliberate: catching a unique violation after a normal INSERT
would leave a PostgreSQL transaction aborted. The receipt tables are accessed
through a persistence adapter, not mutable JPA receipt entities. Native writes
explicitly populate audit fields because JPA listeners do not run for SQL.

## Fingerprints and time

SHA-256 hashes a versioned, length-prefixed binary encoding of every command
field. Recipient IDs are sorted/deduplicated; expiry is normalized to microseconds.
Text whitespace is preserved. The event fingerprint also includes its request key.

Fingerprinting is independent of JSON configuration, input recipient order, and
display timezone. Preserve encoding compatibility for retained keys when evolving
the contract; the encoding version is currently 1.

The injected clock, JSON and JDBC sessions retain `Asia/Phnom_Penh`. Ledger
timestamps retain microsecond precision. Native SQL audit actors are resolved
through the existing `AuditorAware`; anonymous writes have no fallback identity.

## Retention and remaining work

Receipts currently have indefinite retention. Deleting them re-enables old
requests/events. Define a retention horizon against producer retry and broker
replay windows before cleanup. Receipt FKs also block notification deletion
while references remain; lifecycle cleanup must address both.

Large campaigns need resumable fan-out. Delivery leases/recovery, endpoint
verification, templates, Kafka adapters, outbox publication, and production load
testing remain future steps.

## Verification

See [testing and verification](testing.md) for prerequisites and test reports.

```powershell
.\gradlew.bat test bootJar
```

Tests use disposable PostgreSQL and concurrent virtual-thread callers. They cover
equivalent retries, changed payloads, tenant/producer scope, event redelivery,
event-to-request coalescing, invalid recipients, injected outbox failure, rollback
recovery, concurrent winners/conflicts, and waiting on a reservation that rolls back.

## References

- [PostgreSQL READ COMMITTED](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL constraint deferral](https://www.postgresql.org/docs/current/sql-createtable.html)
- [Spring transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
