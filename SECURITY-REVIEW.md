# Security Review Summary

**Review date:** 2026-11-27
**Scope:** Notification service source, CI pipeline, and operational boundaries. This
was a focused review, not a penetration test or a complete infrastructure audit.

## Finding

| Severity | Location | Finding | Confidence |
|---|---|---|---:|
| MEDIUM | `Jenkinsfile` | The pipeline previously loaded `shared-library@master` and explicitly checked out the mutable `main` branch. Changes to either reference could introduce unreviewed code into CI. | 9/10 |

### Remediation and remaining action

The Jenkinsfile no longer names the shared library's floating `master` branch and
now uses `checkout scm` to build the revision selected by the Jenkins job instead
of independently checking out `main`. The shared-library version is controlled by
Jenkins global-library configuration, outside this repository. Configure its
default version to an approved immutable commit SHA and disable version overrides.
Until that Jenkins-side configuration is confirmed, the shared-library pin remains
an open deployment action; removing `@master` alone does not prove that the
configured library version is immutable.

## SonarQube Security Hotspots

| Rule | Review status | Rationale and evidence |
|---|---|---|
| S2245 | Safe | `OutboxWorker` uses `ThreadLocalRandom` only to sample bounded retry jitter through `RetryBackoff`; it does not generate claim tokens or control authorization/lease ownership. Claim ownership uses a separate UUID token. Deterministic tests cover jitter bounds, the 24-hour cap, and `Integer.MAX_VALUE` attempts. |
| S4502 | Safe (application boundary) | `ApiSecurityConfiguration` is stateless and configures JWT resource-server authentication. CSRF is exempted only for `POST /api/v1/notifications`, `PUT /api/v1/inbox/{id}/read`, and `PUT /api/v1/inbox/{id}/archive` when a nonempty explicit `Authorization: Bearer` header is present; no cookie/session authentication mechanism is configured. `NotificationApiTests.ambientSessionCookieCannotAuthenticateStateChangingRequest` confirms that a `JSESSIONID` cookie with a valid CSRF token still receives `401`. Review again if an ingress or proxy starts translating ambient cookies into bearer headers. |

These are source-review dispositions. The Jenkins/SonarQube service is external;
its live hotspot statuses were not changed from this local checkout.

## SonarJava analyzer limitation

The Jenkins diagnostic is retained verbatim: `The method view(InboxItem, Notification) in the type InboxService is not applicable for the arguments (InboxItem, Object)`. Jenkins also emitted `WARN Use of preview features have been detected`.

The project targets Java 27 and has no `--enable-preview` compiler option. The
installed Jenkins SonarJava version was not captured in this repository or the
local test output; the Jenkins shared library and SonarQube installation are
external. Record the exact plugin version from the Jenkins analysis log before
reassessment; do not infer it from the Java or SonarQube server version. Reassess
when a Java-27-compatible analyzer is available, or sooner if the Quality Gate
fails or semantic-analysis problems affect meaningful findings. No suppression
has been added.

## Other reliability and operational gaps

These are incomplete capabilities or deployment dependencies, not additional
confirmed source-code vulnerabilities:

- Phase 2 implements an opt-in persistent outbox worker and publisher contract, but
  no real broker adapter or Kafka consumer is shipped. Test-publisher acknowledgments
  are simulated and do not establish Kafka delivery.
- Notification preferences are persisted but are not enforced during ingestion or
  delivery. Opt-out and missing-preference policies remain to be defined.
- Deduplication receipts have indefinite retention; no cleanup process or retention
  horizon is implemented.
- Production identity/producer-claim provisioning, TLS ingress, distributed rate
  limiting, high availability, backup recovery, and production-load behavior depend
  on external deployment work or remain unverified.

## Verification limits

Automated tests cover application behavior and disposable-PostgreSQL integration,
including JWT validation, tenant isolation, migrations, and restricted database
roles. The controlled outbox verification used disposable PostgreSQL, a test-only
tenant, and the configured test publisher; it did not activate or alter the
persistent local database. The selected outbox and API/security suites passed on
2026-11-27. A fresh worker instance recovered an expired persisted claim, retained
the attempt count and event ID, finalized through the simulated test publisher,
and emitted the asserted outcome/finalization counters, sampled backlog gauge,
and processing timer. A separate test exercised `UNKNOWN` retry followed by test
acknowledgment with the same event ID. Lease expiry was simulated in the disposable
database; no OS process-kill test was performed. These checks do not verify
production infrastructure or end-to-end broker delivery.
