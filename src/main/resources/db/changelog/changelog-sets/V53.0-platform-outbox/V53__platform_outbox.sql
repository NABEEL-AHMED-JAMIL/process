-- The transactional outbox (MIG-22): an event is written here in the same transaction as the
-- state change it announces, and OutboxRelay publishes it to Kafka after that commits. A rollback
-- therefore discards the announcement, and no reader is told about a row it cannot yet see.
CREATE TABLE platform_outbox (
    outbox_id    BIGSERIAL     PRIMARY KEY,
    event_id     VARCHAR(36)   NOT NULL UNIQUE,
    topic        VARCHAR(255)  NOT NULL,
    message_key  VARCHAR(255)  NOT NULL,
    event        TEXT          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT           NOT NULL DEFAULT 0,
    last_error   VARCHAR(2000)
);

-- What the relay reads: the unpublished rows, oldest first.
CREATE INDEX idx_platform_outbox_pending ON platform_outbox (outbox_id) WHERE published_at IS NULL;
