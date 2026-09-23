-- notifications_db, the Notifications service's own database (MIG-21).
--
-- The one source of this table's shape: the changelog runs it on a fresh database, and
-- scripts/notifications/move-to-notifications-db.sh runs it before copying the rows over.
--
-- tenant_id is NOT NULL and leads every index, because it is part of every predicate. It is the
-- RECIPIENT's tenant; 0 is the platform scope, for a platform admin, who has no tenant (tenant ids
-- start at 2900). recipient_user_id is the access-control key and an index key -- never a join
-- target: app_user is in another database.
CREATE TABLE notification (
    notification_id   BIGSERIAL     PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL CHECK (tenant_id >= 0),
    recipient_user_id BIGINT        NOT NULL,
    type              VARCHAR(255)  NOT NULL,
    severity          VARCHAR(255)  NOT NULL,
    title             VARCHAR(255)  NOT NULL,
    message           VARCHAR(2000),
    link_url          VARCHAR(255),
    is_read           BOOLEAN       NOT NULL DEFAULT FALSE,
    read_at           TIMESTAMP,
    date_created      TIMESTAMP     NOT NULL
);

-- The bell's list: one recipient's rows, newest first.
CREATE INDEX idx_notification_inbox ON notification (tenant_id, recipient_user_id, date_created DESC);
-- The badge: one recipient's unread rows.
CREATE INDEX idx_notification_unread ON notification (tenant_id, recipient_user_id) WHERE NOT is_read;
