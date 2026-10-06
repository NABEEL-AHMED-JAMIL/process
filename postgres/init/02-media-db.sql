-- Runs once, when the postgres volume is first created. media_db belongs to Media & Documents
-- (MIG-41); its schema comes from db/media/changelog-master.yaml when process first connects. An
-- existing volume gets it from scripts/media/move-to-media-db.sh instead.
CREATE DATABASE media_db;
