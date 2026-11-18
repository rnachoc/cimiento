-- Transactional outbox: written in the same transaction as each audit entry, published to
-- Kafka afterwards (at least once). Payloads carry identifiers and statuses, never document text.
CREATE TABLE outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    event_key     TEXT NOT NULL,
    event_type    TEXT NOT NULL,
    payload       TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (id) WHERE published_at IS NULL;
