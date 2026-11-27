# outbox-worker-recovery

## Purpose

Define exclusive claims, recoverable leases, ownership fencing, bounded retries, and graceful worker shutdown.

## Requirements

### Requirement: Exclusive valid claims
The system SHALL grant at most one currently valid claim for an event.

#### Scenario: Workers compete
- **WHEN** two workers concurrently claim eligible events
- **THEN** they do not receive overlapping valid ownership of the same event.

### Requirement: Lease recovery
The system SHALL recover abandoned claims after lease expiry.

#### Scenario: Worker crashes
- **WHEN** a worker stops before finalization
- **AND** its lease expires
- **THEN** another authorized worker can recover the event within the retry budget.

### Requirement: Ownership fencing
The system SHALL reject finalization by an expired or superseded claim.

#### Scenario: Old worker resumes
- **WHEN** an expired worker submits a completion
- **THEN** no event state, version, or audit fields are changed by that completion.

#### Scenario: Event is reclaimed
- **WHEN** another worker has obtained a replacement claim
- **THEN** the previous claim cannot finalize success, retry, or failure.

### Requirement: Bounded attempts and backoff
The system SHALL persist attempt accounting and apply configured bounded
exponential backoff with jitter to recoverable failures.

#### Scenario: Recoverable failure
- **WHEN** an attempt fails and budget remains
- **THEN** the event becomes RETRY with a bounded future eligibility time.

#### Scenario: Retry is not yet due
- **WHEN** the retry eligibility time has not arrived
- **THEN** the event is not claimed again.

#### Scenario: Attempt budget exhausted
- **WHEN** the final allowed attempt fails or its claim expires
- **THEN** the event becomes FAILED
- **AND** automatic claiming stops.

### Requirement: Bounded and fair scheduling
The system SHALL bound in-flight work and repeatedly service authorized tenants.

#### Scenario: One tenant has sustained backlog
- **WHEN** multiple authorized tenants have eligible work
- **THEN** a busy tenant does not indefinitely prevent other tenants from being polled.

#### Scenario: Worker capacity is exhausted
- **WHEN** all configured in-flight slots are occupied
- **THEN** the worker does not acquire additional claims until capacity is available.

### Requirement: Recoverable shutdown
The system SHALL stop claiming new work during shutdown and bound its wait for
in-flight publication.

#### Scenario: Shutdown deadline expires
- **WHEN** in-flight publication does not finish before the shutdown deadline
- **THEN** unfinalized events remain recoverable through lease expiry.

#### Scenario: Database polling stalls during shutdown
- **WHEN** a claim operation remains blocked while shutdown starts
- **THEN** the worker stop operation does not wait for that database operation
- **AND** any claim returned afterward is not dispatched and remains lease-recoverable.

### Requirement: Inspectable terminal failures
The system SHALL retain failed events and sanitized failure classifications.

#### Scenario: Failed event awaits recovery
- **WHEN** an event enters FAILED
- **THEN** it remains inspectable
- **AND** ordinary polling cannot reset its attempt budget or requeue it.
