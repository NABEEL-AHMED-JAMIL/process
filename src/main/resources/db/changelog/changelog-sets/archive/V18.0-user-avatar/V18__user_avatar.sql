-- A user's picture is stored in object storage rather than the database; these two columns
-- hold where it went. Both halves are needed: a key alone cannot be resolved back to a
-- provider, since a bucket is only reachable through the storage connection that owns it.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS avatar_bucket VARCHAR(255);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS avatar_key VARCHAR(512);

COMMENT ON COLUMN app_user.avatar_bucket IS 'Bucket holding the user''s picture; null when they have none.';
COMMENT ON COLUMN app_user.avatar_key IS 'Object key of the user''s picture, under the avatars/ prefix.';
