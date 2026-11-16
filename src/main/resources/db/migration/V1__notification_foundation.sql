-- Schema app is provisioned separately. Run as its owner, never as the runtime login.

CREATE TABLE app.recipient (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    issuer varchar(300) NOT NULL,
    subject varchar(180) NOT NULL,
    active boolean NOT NULL,
    UNIQUE (tenant_id, issuer, subject),
    UNIQUE (tenant_id, id)
);

CREATE TABLE app.notification (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    type varchar(100) NOT NULL,
    category varchar(100) NOT NULL,
    title varchar(200) NOT NULL,
    body varchar(10000) NOT NULL,
    expires_at timestamptz,
    UNIQUE (tenant_id, id)
);

CREATE TABLE app.notification_recipient (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    notification_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    read_at timestamptz,
    archived_at timestamptz,
    UNIQUE (tenant_id, notification_id, recipient_id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, notification_id) REFERENCES app.notification (tenant_id, id),
    FOREIGN KEY (tenant_id, recipient_id) REFERENCES app.recipient (tenant_id, id)
);

CREATE TABLE app.delivery (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    inbox_item_id uuid NOT NULL,
    channel varchar(20) NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'PUSH')),
    destination_key varchar(200) NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'ACCEPTED', 'RETRY_WAIT', 'FAILED_FINAL')),
    attempt_count integer NOT NULL CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    UNIQUE (tenant_id, inbox_item_id, channel, destination_key),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, inbox_item_id) REFERENCES app.notification_recipient (tenant_id, id)
);

CREATE TABLE app.notification_preference (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    recipient_id uuid NOT NULL,
    category varchar(100) NOT NULL,
    channel varchar(20) NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'PUSH')),
    enabled boolean NOT NULL,
    UNIQUE (tenant_id, recipient_id, category, channel),
    FOREIGN KEY (tenant_id, recipient_id) REFERENCES app.recipient (tenant_id, id)
);

CREATE TABLE app.outbox_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by varchar(512) NOT NULL,
    updated_by varchar(512) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    payload varchar(20000) NOT NULL,
    published_at timestamptz
);

CREATE INDEX inbox_page_idx ON app.notification_recipient (tenant_id, recipient_id, created_at DESC, id DESC);
CREATE INDEX inbox_unread_idx ON app.notification_recipient (tenant_id, recipient_id, created_at DESC, id DESC)
    WHERE read_at IS NULL AND archived_at IS NULL;
CREATE INDEX delivery_due_idx ON app.delivery (status, next_attempt_at);
CREATE INDEX outbox_unpublished_idx ON app.outbox_event (created_at, id) WHERE published_at IS NULL;
