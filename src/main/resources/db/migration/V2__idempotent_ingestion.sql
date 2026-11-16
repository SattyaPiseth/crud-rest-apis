-- Reserve before business writes; the FK is checked at commit, while the unique
-- conflict arbiter remains immediate. A reservation cannot commit without a result.
CREATE TABLE app.ingestion_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    producer_id varchar(100) NOT NULL,
    request_hash varchar(64) NOT NULL CHECK (length(request_hash) = 64),
    notification_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    UNIQUE (tenant_id, producer_id, idempotency_key),
    FOREIGN KEY (tenant_id, notification_id) REFERENCES app.notification (tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE app.processed_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    producer_id varchar(100) NOT NULL,
    request_hash varchar(64) NOT NULL CHECK (length(request_hash) = 64),
    notification_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    consumer_name varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    UNIQUE (tenant_id, producer_id, consumer_name, event_id),
    FOREIGN KEY (tenant_id, notification_id) REFERENCES app.notification (tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ingestion_notification_idx ON app.ingestion_request (tenant_id, notification_id);
CREATE INDEX processed_event_notification_idx ON app.processed_event (tenant_id, notification_id);
