-- Forward-only lifecycle extension. The temporary policy is restricted to the
-- migration identity and exists only within Flyway's migration transaction.
-- ENABLE/FORCE RLS and the runtime policy remain in place throughout.
ALTER TABLE app.outbox_event
    ADD COLUMN state varchar(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN claim_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN failure_code varchar(40);
CREATE POLICY outbox_v5_backfill ON app.outbox_event TO CURRENT_USER USING (true) WITH CHECK (true);
UPDATE app.outbox_event SET state = 'PUBLISHED' WHERE published_at IS NOT NULL;
DROP POLICY outbox_v5_backfill ON app.outbox_event;
ALTER TABLE app.outbox_event
    ADD CONSTRAINT outbox_state CHECK (state IN ('PENDING','PROCESSING','RETRY','PUBLISHED','FAILED')),
    ADD CONSTRAINT outbox_attempts CHECK (attempt_count >= 0),
    ADD CONSTRAINT outbox_lease CHECK (
        (state = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL AND attempt_count > 0)
        OR (state <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)),
    ADD CONSTRAINT outbox_retry CHECK ((state = 'RETRY') = (next_attempt_at IS NOT NULL)),
    ADD CONSTRAINT outbox_published CHECK ((state = 'PUBLISHED') = (published_at IS NOT NULL)),
    ADD CONSTRAINT outbox_failure CHECK (failure_code IS NULL OR failure_code IN
        ('RETRYABLE','PERMANENT','UNKNOWN','EXHAUSTED'));
DROP INDEX app.outbox_unpublished_idx;
CREATE INDEX outbox_due_idx ON app.outbox_event (tenant_id, next_attempt_at, created_at, id)
    WHERE state IN ('PENDING','RETRY');
CREATE INDEX outbox_lease_idx ON app.outbox_event (tenant_id, lease_until, created_at, id)
    WHERE state = 'PROCESSING';
