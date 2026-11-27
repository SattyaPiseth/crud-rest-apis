# Phase 2 persistent-worker operational acceptance

Date: 2026-11-27. Scope: controlled local verification of persistent outbox state,
application-process-kill recovery, retry, shutdown, metrics, and tenant isolation.

## Decision and revision

**Final Phase 2 operational decision: PASS for the tested persistent outbox worker only.**
Evidence is limited to the local worker lifecycle using a deterministic test publisher
and simulated acknowledgments; it does not establish Kafka delivery, broker acknowledgment,
consumer deduplication, production readiness, or a passing live Jenkins/SonarQube gate.

- Application base revision: `16e7c9d83b5127b3539b6e0acc59cea29e549c22`.
- The operational harness and Gradle task are test-only tooling in `build.gradle` and
  `src/test/java/sattya/piseth/crudrestapis/outbox/publication/PersistentOutboxOpsProcess.java`.
  No production application source was changed. Existing `.codex/` content was left
  untouched and excluded.
- `app.outbox.enabled=true` was supplied only by the harness. The regular application
  default remains disabled.

## Isolated environment and activation

- Dedicated container: `outbox-ops-pg-16e7c9d`, image `postgres:18.7-trixie`, database
  `outbox_ops`, bound only to `127.0.0.1:55432`, with named persistent volume
  `outbox-ops-pgdata-16e7c9d`. After evidence capture, the container was stopped and
  removed; the named volume remains intact and was not deleted.
- This database is separate from the populated Compose database. No Compose database,
  real tenant membership, production data, Kafka, or RabbitMQ was used.
- Migrations V1–V5 were applied to the new database. The harness explicitly enabled the
  worker for one generated tenant with concurrency 1, batch size 1, 20 ms polling,
  100 ms initial retry, 250 ms maximum retry, 60 s publication timeout, 70 s lease,
  and 5 s finalization margin. The harness set `app.api.enabled=false` and bound its
  servlet context to `127.0.0.1` on an ephemeral port.
- Admin, application, and worker database passwords are required from environment
  variables; credential values and claim tokens are not recorded in this report.
- The first process used a test-only `hold` adapter that never acknowledges. On restart,
  the adapter returned `UNKNOWN` once, then `ACKNOWLEDGED`. These are simulated outcomes,
  not external publication acknowledgments.

## Persisted state and process-kill recovery

Generated fixture: tenant `de4e4b7f-8b5a-4f4c-9f20-7bc929d99b02`, event
`5ca62910-59ea-45e8-bbc0-9c2a94f3f42c`.

1. The fixture began as `PENDING`, attempt 0.
2. The first JVM ran in `hold` mode with a 60-second publication timeout. The database
   showed its claim as `PROCESSING`, attempt 1.
3. A state-triggered controller observed `PROCESSING:1` and force-stopped only the
   verified harness JVM (PID 9824). The database still showed `PROCESSING`, attempt 1,
   a claim token (redacted), and lease expiry `2026-11-27 06:58:10.894433+00`.
   PostgreSQL reported zero active `crud_outbox` sessions after the kill.
4. A fresh JVM (PID 29812) started against the same database and tenant. It waited for
   the persisted lease to expire; the first restarted publisher call returned `UNKNOWN`,
   after which the database log showed `RETRY` at attempt 2. The next call was followed
   by logged `PROCESSING` and `PUBLISHED` states at attempt 3. The event ID remained
   unchanged. Final `published_at` was `2026-11-27 13:58:11.100087` local time.
5. The restarted application exited normally: Tomcat completed graceful shutdown and
   both the worker and API Hikari pools closed. The forced termination in step 3 did
   not rely on graceful shutdown.

Representative captured harness log:

```text
OPS_DATABASE_STATE state=PENDING attempts=0 forced_rls=true
OPS_WORKER_STARTED running=true tenant=de4e4b7f-8b5a-4f4c-9f20-7bc929d99b02
OPS_STATE state=PROCESSING attempts=1 published_at=null
OPS_PUBLISHER mode=retry-then-ack call=1 event=5ca62910-59ea-45e8-bbc0-9c2a94f3f42c timeout_ms=60000
OPS_STATE state=RETRY attempts=2 published_at=null
OPS_PUBLISHER mode=retry-then-ack call=2 event=5ca62910-59ea-45e8-bbc0-9c2a94f3f42c timeout_ms=60000
OPS_STATE state=PROCESSING attempts=3 published_at=null
OPS_STATE state=PUBLISHED attempts=3 published_at=2026-11-27 13:58:11.100087
OPS_TENANT_ISOLATION authorized_visible=true other_tenant_visible=false role=crud_outbox
OPS_METRICS unknown=1.0 acknowledged=1.0 finalizations_applied=2.0 processing_count=2 published_gauge=1
OPS_RESULT event=5ca62910-59ea-45e8-bbc0-9c2a94f3f42c tenant=de4e4b7f-8b5a-4f4c-9f20-7bc929d99b02 state=PUBLISHED attempts=3 simulated_ack=true kafka_delivery=false
```

Two harness-only exploratory failures were excluded from acceptance: a non-web context
failed before worker startup because it lacked the servlet security context, and a
subsequent 8-second hold run exceeded the manual controller window and reached `FAILED`.
The harness was stopped and zero worker sessions confirmed; only that harness-owned row
was removed and reseeded in the isolated database before the successful 60-second
state-triggered process-kill run.

## Metrics, database security, and regression tests

- In-memory worker metrics after recovery: `UNKNOWN` attempts 1; `ACKNOWLEDGED` attempts
  1; applied finalizations 2; processing timer count 2; `PUBLISHED` backlog gauge 1.
- Final database state: `PUBLISHED`, attempt count 3, with the original tenant and event
  IDs. `relrowsecurity=true` and `relforcerowsecurity=true` on `app.outbox_event`.
- `crud_outbox` is non-superuser, has no `BYPASSRLS`, role inheritance, database/role
  creation, or memberships. It has outbox `SELECT` and lifecycle-column `UPDATE`; it
  has no table-wide `UPDATE`, no `tenant_id` update, and no `INSERT` or `DELETE`.
  `crud_owner` is `NOLOGIN`. Worker database sessions returned to zero after shutdown.
- Direct checks as `crud_outbox` saw the fixture with its configured tenant and saw zero
  matching rows after changing to a different tenant context.
- Focused Gradle regression run passed: 37 tests, 0 failures, 0 errors, 0 skipped across
  `OutboxConfigurationTests` (7), `OutboxStoreTests` (25), `OutboxWorkerTests` (3), and
  `OutboxEnabledApplicationTests` (2). The test-only configuration is marked
  `@TestConfiguration` so it is excluded from normal application component scanning.
- After moving app and worker passwords to required environment variables, the opt-in
  `probe` task was rerun with fresh local credentials: Flyway validated all five
  migrations, the worker started and shut down cleanly, and the event remained
  `PUBLISHED` at attempt 3. The full process-kill/retry sequence above was captured
  before this credential-provisioning-only change and was not repeated afterward.

## Remaining gate

The accepted scope verifies persistence across an actual application-process kill,
lease-expiry recovery, retry, normal shutdown, selected metrics, and the worker role's
tenant boundary in a dedicated local database. Broker delivery and consumer-side
deduplication remain Phase 3 acceptance work; live Jenkins/SonarQube verification and
production infrastructure readiness remain unverified.