# Security Review Summary

**Review date:** 2026-11-19
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

## Other reliability and operational gaps

These are incomplete capabilities or deployment dependencies, not additional
confirmed source-code vulnerabilities:

- The application creates outbox events, but has no outbox publisher, Kafka
  listener, or external delivery worker/provider. External notification delivery
  and its retry/recovery behavior are not implemented.
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
roles. They do not verify production infrastructure or end-to-end broker delivery.
