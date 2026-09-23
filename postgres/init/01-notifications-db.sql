-- Runs once, when the postgres volume is first created. notifications_db belongs to the
-- Notifications service (MIG-21); its schema comes from db/notifications/changelog-master.yaml
-- when process first connects. An existing volume gets it from
-- scripts/notifications/move-to-notifications-db.sh instead.
CREATE DATABASE notifications_db;
