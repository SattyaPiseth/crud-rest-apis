-- Run once as an authorized database administrator AFTER V5. Never run at API startup.
-- Supply OUTBOX_DB_PASSWORD in the administrator's environment, not in source control.
\set ON_ERROR_STOP on
\getenv worker_password OUTBOX_DB_PASSWORD
BEGIN;
CREATE ROLE crud_outbox LOGIN PASSWORD :'worker_password'
    NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE crud_rest_apis TO crud_outbox;
GRANT USAGE ON SCHEMA app TO crud_outbox;
GRANT SELECT ON app.outbox_event TO crud_outbox;
GRANT UPDATE (state, attempt_count, next_attempt_at, claim_token, lease_until,
    failure_code, published_at, updated_at, updated_by, version) ON app.outbox_event TO crud_outbox;
ALTER ROLE crud_outbox SET statement_timeout = '5s';
ALTER ROLE crud_outbox SET lock_timeout = '1s';
ALTER ROLE crud_outbox SET idle_in_transaction_session_timeout = '10s';
COMMIT;
