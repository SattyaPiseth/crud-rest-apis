# Domain model and persistence conventions

[Documentation index](../README.md) · [Project overview](../../README.md)

See [the ER diagram and relationship guide](er-diagram.md) for the implemented
schema, tenant-aware foreign keys, and the rationale for UUID references.

See [idempotent ingestion and consumer deduplication](../development/idempotent-ingestion.md)
for V2, service usage, transaction boundaries and concurrency tests.

This project targets JDK 27 with Spring Boot's managed dependency versions. No
preview features are enabled. Run Gradle and IntelliJ with JDK 27.

## Dependencies

[build.gradle](../../build.gradle) includes MVC (REST/SSE), JPA, validation, PostgreSQL, Actuator,
Spring Security, OAuth2 resource-server support, Kafka, and Flyway. Auditing uses
Spring Data JPA plus `spring-aspects`; there is no separate SpringAuditor starter.
Testcontainers supplies disposable PostgreSQL for integration tests.

Kafka integration is available on the classpath. There are no Kafka listeners,
topics, workers, broker deployment, or network deliveries in this first step.
Add the mail starter when implementing the email adapter.

## Package structure

All packages are under `sattya.piseth.crudrestapis`:

```text
config/                    JPA auditing and temporary deny-all HTTP security
shared/domain/             TenantEntity, AuditedEntity, validation helpers
shared/audit/              ActorProvider implementations and SpringAuditorAware
recipient/domain/          External identity reference, without credentials
notification/domain/       Notification content snapshot
notification/application/  Ingestion commands, services, consumer and store contract
notification/persistence/  PostgreSQL deduplication adapter
inbox/domain/              Recipient-specific read/archive state
inbox/persistence/         Ownership-scoped repository
delivery/domain/           Channel, delivery status and guarded transitions
preference/domain/         Recipient/category/channel opt-in
outbox/domain/             Event waiting for publication
infrastructure/migration/ Separate Flyway entry point
```

Add `application`, `web`, and adapter packages within features as their behavior
is implemented. Do not introduce empty controller/service interfaces just to fill
out a folder structure.

## Entities and OOP

* **Encapsulation:** private state, getters, Lombok protected no-arg constructors, validated
  public constructors and intent-specific methods (`markRead`, `archive`,
  `startAttempt`, `accept`). No public setters or Lombok `@Data` on entities.
* **Abstraction:** `ActorProvider` describes actor resolution without exposing
  whether the actor comes from Spring Security or a background job.
* **Inheritance:** `TenantEntity` holds UUID identity and optimistic version;
  `AuditedEntity` adds timestamps and actors. Concrete entities inherit this
  persistence contract, rather than creating artificial email/SMS entity trees.
* **Polymorphism:** `SpringAuditorAware` invokes ordered `ActorProvider`
  implementations through the same interface.

JPA entities remain non-final classes for ORM compatibility. Final classes are
used for infrastructure implementations that do not require subclass proxies.
`ScopedValue` provides lexical background identity; pattern matching handles JWT
authentication. Records represent the implemented ingestion commands and results;
they are not mutable managed entities. Modern Java features serve the design.

References between aggregates use UUIDs, avoiding eager graphs and accidental
cascades. The migration enforces actual foreign keys, including tenant identity.
The domain constructor also rejects cross-tenant inbox relationships. All service
operations must still authorize the caller and scope queries by tenant/owner.

`Notification` is a content snapshot. `InboxItem` owns read/archive state;
`Delivery` owns channel progress. `ACCEPTED` means provider acceptance, not read
or confirmed delivery. `destinationKey` is an opaque endpoint reference; endpoint
verification/encrypted storage comes with the delivery adapter step. Do not send
to an arbitrary address supplied in this field.

## Auditing and timezones

`JpaAuditConfiguration` enables auditing and uses the clock configured by
`TimeConfiguration` for `Asia/Phnom_Penh` (UTC+07:00). Entity listeners
populate `createdAt`, `updatedAt`, `createdBy`, and `updatedBy`.

Timestamps remain `Instant` in Java and `timestamptz` in PostgreSQL, preserving
the actual point in time. An Instant has no local timezone; its `toString()` uses
`Z`. The application's Jackson mapper renders all Instant values with the Phnom
Penh offset, for example `2026-11-16T17:00:00+07:00` for `2026-11-16T10:00:00Z`.
JDBC sessions and application log timestamps also use `Asia/Phnom_Penh`.
The Compose server timezone takes effect when its container is recreated;
application connections set their own session timezone on connection creation.
No existing timestamp values need to be shifted or migrated.

HTTP actors are derived from authenticated Spring Security context. JWT identity
includes issuer and subject. There is deliberately no anonymous-to-system
fallback. An audited write without an actor fails.

Background workers must bind an explicit service actor around the **whole
transaction**, including commit/flush:

```java
backgroundActor.callAs("notification-ingestion", () ->
    transactionTemplate.execute(status -> {
        entityManager.persist(notification);
        entityManager.persist(inboxItem);
        entityManager.persist(outboxEvent);
        return notification.getId();
    })
);
```

With `@Transactional`, call a separate proxied service inside `callAs`; do not
bind the actor only inside a method whose transaction commits after it returns.
Scopes restore after exceptions and nested calls. They do not automatically
propagate into arbitrary executor tasks; bind the actor in the worker itself.
Use trusted service names, never a request body or unverified Kafka actor field.

These fields retain creation and the latest change. They are not a full security
audit history. Bulk/native SQL bypasses entity listeners and must set audit
metadata explicitly. Read-only requests do not require an audit actor.

## Database migration

Runtime Flyway is disabled and Hibernate remains `ddl-auto=validate` because
`crud_app` intentionally has no DDL privileges. `V1__notification_foundation.sql`
creates the six tables, uniqueness constraints, tenant-aware foreign keys and
indexes. V2 adds ingestion and processed-event receipts. Existing databases are
not modified by the tests. See the [database operations guide](../operations/database.md)
for local setup, migration credentials, and maintenance.

Provision a dedicated migration login outside the application, permit it to
connect to the database and `SET ROLE crud_owner`, and keep its credentials out
of source control. The migrator sets that role before Flyway creates objects;
the existing owner's default privileges then grant runtime CRUD access.

Supply these environment variables securely:

```text
MIGRATION_DB_URL       JDBC URL for the target PostgreSQL database
MIGRATION_DB_USERNAME  Dedicated migration login
MIGRATION_DB_PASSWORD Its secret
```

Then run from the project root:

```powershell
.\gradlew.bat migrateDatabase
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

The `app` schema must already exist (the local database initialization creates
it). Do not use the runtime login for migrations or enable `ddl-auto=update`.
Do not edit an applied migration. Add the next version for later schema changes.

## Verification

Start Docker Desktop with Linux containers and run:

```powershell
.\gradlew.bat test
```

The integration suite starts a disposable PostgreSQL container, runs Flyway,
validates Hibernate mappings, and tests auditing, ownership lookup, database
tenant constraints and optimistic locking. It does not use `.secrets` or the
persistent Compose database. Docker is required; unavailable Docker fails the
integration test instead of silently skipping it.

See [testing and verification](../development/testing.md) for the full suite,
coverage, report locations and troubleshooting.

Domain and audit unit tests can run alone:

```powershell
.\gradlew.bat test --tests '*NotificationDomainTests' --tests '*SpringAuditorAwareTests'
```

## Remaining implementation work

1. Extend in-app ingestion with templates, endpoint management, external-channel
   delivery jobs, and delivery-attempt history as needed.
2. Configure the real identity provider, issuer/audience validation, scopes and
   tenant membership resolution. HTTP currently denies every request; no login,
   notification API, or SSE endpoint is exposed in this stage.
3. Connect the transactional consumer to Kafka; implement the outbox relay,
   delivery leases/crash recovery, retries and dead-letter handling. Never treat database
   writes plus Kafka sends as one automatically atomic transaction.
4. Add SSE with authenticated ownership, reconnection and multi-instance routing.

The delivery state methods are a domain foundation, not a concurrent worker
implementation. Workers must add claim/lease recovery and retry policy before
they can safely execute external deliveries. Outbox marking likewise must follow
broker acknowledgement and tolerate duplicate publication.

## References

[Spring Data JPA auditing](https://docs.spring.io/spring-data/jpa/reference/auditing.html),
[Spring Boot](https://docs.spring.io/spring-boot/reference/),
[Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/).
