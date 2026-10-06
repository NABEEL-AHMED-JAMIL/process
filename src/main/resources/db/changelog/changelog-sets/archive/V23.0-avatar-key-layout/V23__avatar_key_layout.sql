-- The comment written in V18 describes a layout that no longer exists. Pictures were stored
-- flat as avatars/<appUserId>.<ext>; they now live in a folder per person, which keeps
-- everything belonging to a user together and lets it be removed with them.
--
-- Corrected in a new changeset rather than by editing V18, because that one has already run
-- everywhere and changing it would fail Liquibase's checksum on the next start.
COMMENT ON COLUMN app_user.avatar_key IS
  'Object key of the user''s picture: <appUserId>/profile/avatar.<ext>.';
