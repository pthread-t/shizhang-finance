CREATE TABLE attachment_uploads (
    id UUID PRIMARY KEY,
    attachment_id UUID NOT NULL UNIQUE,
    book_id UUID NOT NULL REFERENCES books(id),
    transaction_id UUID NOT NULL,
    display_name TEXT NOT NULL,
    mime_type VARCHAR(120) NOT NULL,
    expected_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    received_bytes BIGINT NOT NULL DEFAULT 0,
    uploaded_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours'
);
CREATE INDEX attachment_uploads_expiry_idx ON attachment_uploads(expires_at);
