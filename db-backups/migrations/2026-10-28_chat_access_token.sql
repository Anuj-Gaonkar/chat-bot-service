-- Link tokens for the chat-ui gate: minted when the CTA WhatsApp message is sent, mapped to the
-- recipient's phone number (digits only, last 10), valid for 12 hours and reusable until then.
-- Hibernate ddl-auto=update creates this automatically in dev; this is for environments that don't.

CREATE TABLE IF NOT EXISTS chat_access_token (
    token        VARCHAR(64) PRIMARY KEY,
    phone_number VARCHAR(10) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_access_token_phone ON chat_access_token (phone_number);
