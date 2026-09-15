-- Where the SECOND dataset of a saved query lives.
--
-- A query in this module may read two files at once: DuckDbAnalyticsEngine registers the first as
-- the view `dataset` and, when a second is given, the other as `dataset2`, so a person can write
-- an ordinary join. That has worked for an ad-hoc run since the feature shipped. It has never
-- worked for a SAVED one, and this changeset is the half of the fix the database owns.
--
-- What was actually wrong, because it explains why two nullable columns are the whole schema
-- change: the second dataset was dropped at four independent points between the Studio and a
-- dashboard tile, and this table was one of them. V32 gave analytics_query exactly one location
-- -- connection_alias + dataset_path -- so even a client that sent a second pair had nowhere to
-- put it. The row kept the JOIN text and half its inputs, and the failure surfaced later and
-- somewhere else: reopening the query registered no `dataset2`, and DuckDB answered "Catalog
-- Error: Table with name dataset2 does not exist!" -- an engine-internal sentence, shown to a
-- reader who had done nothing wrong.
--
-- NULLABLE, and as a PAIR. Nearly every saved query reads one file and must stay exactly as it
-- is, so these cannot be NOT NULL and there is no default worth inventing. The pair rule -- both
-- set or neither -- is the same one AnalyticsRestApi already applies on the wire for an ad-hoc
-- run, and it is enforced here as a CHECK rather than only in Java, because a half-populated row
-- is unreadable by definition: a path with no connection cannot be resolved, and a connection
-- with no path names no file.
--
-- The ALIAS again, never the bucket, matching connection_alias above it and for the same reason
-- V32 recorded: a connection later repointed at a different bucket carries its saved queries with
-- it, and a stored bucket name would quietly start reading the wrong place.

ALTER TABLE analytics_query
    ADD COLUMN IF NOT EXISTS second_connection_alias VARCHAR(255),
    ADD COLUMN IF NOT EXISTS second_dataset_path TEXT;

COMMENT ON COLUMN analytics_query.second_connection_alias IS
    'Connection alias of the second dataset, registered as the view dataset2. Null when the query reads one file. Set only together with second_dataset_path.';

COMMENT ON COLUMN analytics_query.second_dataset_path IS
    'Object key or glob of the second dataset, registered as the view dataset2. Null when the query reads one file. Set only together with second_connection_alias.';

-- Both or neither. A row with one half of the pair describes a location that cannot be resolved,
-- so it is refused at the table rather than discovered by a reader when their query will not run.
ALTER TABLE analytics_query
    DROP CONSTRAINT IF EXISTS ck_analytics_query_second_dataset_pair;

ALTER TABLE analytics_query
    ADD CONSTRAINT ck_analytics_query_second_dataset_pair
    CHECK ((second_connection_alias IS NULL AND second_dataset_path IS NULL)
        OR (second_connection_alias IS NOT NULL AND second_dataset_path IS NOT NULL));

-- ---------------------------------------------------------------------------------------------
-- analytics_query_run: the same pair, for the same reason, on the table that answers "who read
-- what".
-- ---------------------------------------------------------------------------------------------
--
-- A run row named one location even when the statement read two. That is a smaller bug than the
-- saved-query one -- nothing breaks, the query runs correctly -- and a worse one to leave,
-- because this table exists to be the record afterwards. An audit line saying a person read
-- orders.csv, when what they actually ran joined orders.csv to customers.csv, is not an
-- incomplete answer to "who read what": it is a wrong one, and nothing else on the row hints
-- that a second file was involved.
--
-- Nullable and unconstrained-as-a-pair here, deliberately unlike analytics_query above. A saved
-- query is a thing to be re-run, so half a location makes it unrunnable and the table refuses it.
-- A run row is a record of something that already happened, and the one duty a record has is to
-- accept what it is given: a CHECK here could reject an audit write, and losing the audit line is
-- strictly worse than storing an odd one.

ALTER TABLE analytics_query_run
    ADD COLUMN IF NOT EXISTS second_connection_alias VARCHAR(255),
    ADD COLUMN IF NOT EXISTS second_dataset_path TEXT;

COMMENT ON COLUMN analytics_query_run.second_connection_alias IS
    'Connection alias of the second dataset this run read as dataset2, or null when it read one file.';

COMMENT ON COLUMN analytics_query_run.second_dataset_path IS
    'Object key or glob of the second dataset this run read as dataset2, or null when it read one file.';
