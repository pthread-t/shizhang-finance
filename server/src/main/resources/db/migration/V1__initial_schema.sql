CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    recovery_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_at TIMESTAMPTZ
);

CREATE TABLE devices (
    id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(120) NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    PRIMARY KEY (id, user_id)
);

CREATE TABLE refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    device_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_user_device_idx ON refresh_tokens(user_id, device_id);

CREATE TABLE books (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    owner_id UUID NOT NULL REFERENCES users(id),
    server_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE memberships (
    book_id UUID NOT NULL REFERENCES books(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    PRIMARY KEY (book_id, user_id)
);
CREATE INDEX memberships_user_idx ON memberships(user_id) WHERE removed_at IS NULL;

CREATE TABLE invites (
    code_hash CHAR(64) PRIMARY KEY,
    book_id UUID NOT NULL REFERENCES books(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('EDITOR', 'VIEWER')),
    created_by UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    used_by UUID REFERENCES users(id),
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sync_entities (
    book_id UUID NOT NULL REFERENCES books(id),
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    version BIGINT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    field_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    deleted BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (book_id, entity_type, entity_id)
);
CREATE INDEX sync_entities_book_type_idx ON sync_entities(book_id, entity_type) WHERE deleted = false;

CREATE TABLE sync_changes (
    book_id UUID NOT NULL REFERENCES books(id),
    book_sequence BIGINT NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    version BIGINT NOT NULL,
    payload JSONB NOT NULL,
    deleted BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (book_id, book_sequence)
);

CREATE TABLE processed_operations (
    operation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    book_id UUID NOT NULL REFERENCES books(id),
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    resulting_version BIGINT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    book_id UUID NOT NULL REFERENCES books(id),
    transaction_id UUID NOT NULL,
    storage_key TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    mime_type VARCHAR(120) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX attachments_book_transaction_idx ON attachments(book_id, transaction_id) WHERE deleted_at IS NULL;

CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    book_id UUID NOT NULL REFERENCES books(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(24) NOT NULL,
    changed_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_book_time_idx ON audit_events(book_id, occurred_at DESC);

