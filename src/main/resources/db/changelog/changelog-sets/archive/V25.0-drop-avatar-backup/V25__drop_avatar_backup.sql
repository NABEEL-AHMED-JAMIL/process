-- A snapshot of app_user's avatar columns taken by hand before the 24 August migration, kept in
-- case that migration went wrong. It did not, and the table has been empty since.
--
-- Dropped through Liquibase rather than a direct DROP so every environment loses it at the same
-- point in the chain; a table removed by hand on one machine and left standing on another is
-- the kind of drift nobody notices until a restore.
--
-- IF EXISTS because environments provisioned after that migration never had it.
DROP TABLE IF EXISTS avatar_backup_20260824;
