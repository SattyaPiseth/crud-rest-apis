# outbox-publication

## Purpose

Define reliable asynchronous publication of committed transactional outbox events, preserving stable identity and explicit acknowledgment semantics.

## Requirements

### Requirement: Automatic asynchronous processing
The system SHALL automatically process eligible committed outbox events through
background workers when explicitly enabled with valid configuration and an adapter,
without requiring HTTP requests to trigger publication.

#### Scenario: Enabled worker processes committed events independently
- **WHEN** the configured worker starts and an authorized tenant has eligible committed events
- **THEN** background polling claims and processes those events without an HTTP trigger
- **AND** external publication executes outside the ingestion and claim transactions.

#### Scenario: Polling temporarily fails
- **WHEN** a polling operation fails because of a transient database error
- **THEN** subsequent scheduled polls continue
- **AND** exception details are not exposed in application logs.

### Requirement: Preserve transactional acceptance
The system SHALL publish only committed outbox events without changing notification
acceptance or idempotency behavior.

#### Scenario: Ingestion commits
- **WHEN** ingestion successfully commits
- **THEN** its outbox event is available for eligible background processing.

#### Scenario: Ingestion rolls back
- **WHEN** ingestion fails and rolls back
- **THEN** no corresponding event is available for publication.

#### Scenario: Accepted request is replayed
- **WHEN** existing idempotency rules identify a request replay
- **THEN** publication processing does not cause another outbox event to be created.

### Requirement: Stable publication envelope
The system SHALL preserve event ID, tenant ID, aggregate ID, event type, schema
version, and payload across attempts.

#### Scenario: Event is retried
- **WHEN** an event is published again
- **THEN** its envelope identity and business payload remain unchanged
- **AND** attempt-specific metadata does not replace the stable event ID.

### Requirement: Explicit acknowledgment
The system SHALL record publication success only after explicit destination
acceptance and a successful ownership-checked finalization.

#### Scenario: Destination acknowledges
- **WHEN** acceptance is acknowledged while the claim remains valid
- **THEN** the event becomes PUBLISHED and receives a publication timestamp.

#### Scenario: Outcome is unknown
- **WHEN** publication times out or acknowledgment is lost
- **THEN** the event is not marked PUBLISHED
- **AND** it follows the recoverable failure policy.

#### Scenario: Destination permanently rejects
- **WHEN** the adapter reports a permanent failure
- **THEN** the event becomes FAILED without automatic retry.

#### Scenario: Database finalization fails after acceptance
- **WHEN** a destination accepts an event but its publication-state transaction fails
- **THEN** no partial finalization is committed
- **AND** recovery can republish the same stable event ID.

### Requirement: Explicit lifecycle
The system SHALL expose PENDING, PROCESSING, RETRY, PUBLISHED, and FAILED states
and reject invalid transitions without changing persisted state.

#### Scenario: Terminal event is polled
- **WHEN** an event is PUBLISHED or FAILED
- **THEN** normal polling does not claim it.

#### Scenario: Invalid transition is attempted
- **WHEN** a transition violates the permitted lifecycle
- **THEN** it is rejected without changing state, version, or audit fields.

### Requirement: At-least-once behavior
The system SHALL permit duplicate publication after uncertain outcomes and SHALL
NOT claim exactly-once delivery or guaranteed delivery after retry exhaustion.

#### Scenario: Crash after acceptance
- **WHEN** the destination accepts an event but success is not persisted
- **THEN** recovery may publish the same stable event ID again.

### Requirement: Safe adapter activation
The system SHALL remain disabled by default and SHALL NOT silently substitute
a success-reporting adapter when publication is enabled.

#### Scenario: Enabled without adapter
- **WHEN** worker activation is requested without a configured publication adapter
- **THEN** activation fails closed with a sanitized configuration error.

#### Scenario: Worker remains disabled
- **WHEN** worker activation is not configured
- **THEN** no events are claimed or marked published by background processing.

#### Scenario: Multiple adapters are registered
- **WHEN** more than one publication adapter is registered, even if one is preferred
- **THEN** worker activation fails before any publication begins.
