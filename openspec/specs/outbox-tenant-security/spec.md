# outbox-tenant-security

## Purpose

Preserve trusted tenant authorization, PostgreSQL row-level security, restricted worker privileges, and background audit attribution.

## Requirements

### Requirement: Trusted tenant authorization
The system SHALL obtain authorized tenant IDs from trusted backend configuration,
not event payloads, request input, or unscoped discovery of tenant-owned rows.

#### Scenario: Tenant not authorized for worker
- **WHEN** an event belongs to a tenant outside the worker's approved tenant set
- **THEN** that worker does not claim or publish the event.

#### Scenario: Enabled without authorized tenants
- **WHEN** a worker is enabled without a valid nonempty authorized tenant set
- **THEN** activation fails closed.

### Requirement: Transaction-local tenant isolation
The system SHALL establish tenant context separately for every protected database
transaction while retaining forced RLS.

#### Scenario: Context missing
- **WHEN** a protected operation executes without tenant context
- **THEN** it cannot access or mutate tenant-owned events.

#### Scenario: Connection reused
- **WHEN** a pooled connection is reused for another tenant
- **THEN** previous transaction context does not authorize the new operation.

#### Scenario: Cross-tenant finalization is attempted
- **WHEN** a finalization targets an event outside its transaction's tenant context
- **THEN** the event's state, version, and audit fields remain unchanged.

### Requirement: Restricted worker privileges
The system SHALL use a worker database identity with only the outbox access
required for processing.

#### Scenario: Unrelated write attempted
- **WHEN** the worker role attempts to modify recipients, notifications,
  security policies, or migration history
- **THEN** PostgreSQL rejects the operation.

#### Scenario: Event identity mutation attempted
- **WHEN** the worker role attempts to change an event's tenant, payload,
  identity, or creation audit fields
- **THEN** PostgreSQL rejects the operation.

#### Scenario: Worker privileges are misconfigured
- **WHEN** required outbox read privileges are absent or forbidden table/column privileges are granted
- **THEN** worker activation fails closed.

### Requirement: Background audit attribution
The system SHALL preserve creation audit fields and record an explicit approved
background actor for successful worker state changes.

#### Scenario: Worker changes state
- **WHEN** a claim, retry, recovery, or finalization succeeds
- **THEN** update audit fields and optimistic version are maintained
- **AND** original producer creation identity remains unchanged.

### Requirement: Sanitized observability
The system SHALL expose operational health without logging event payloads,
tokens, credentials, or raw destination error bodies.

#### Scenario: Publication fails
- **WHEN** a failure is recorded or logged
- **THEN** diagnostics contain a bounded failure classification
- **AND** metrics avoid tenant, recipient, and event ID labels.
