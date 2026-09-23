-- Saved queries, and the record that one ran.
--
-- Two tables rather than one, because they answer to different owners. analytics_query is a
-- person's own work -- something they named, come back to and edit. analytics_query_run is the
-- module's first "who read what, and when" record (the gap the studio has carried since phase one
-- shipped with no audit row and no event), and evidence is not editable by the person it is about.
-- Folding a last_run_at and a last_row_count onto the saved query instead would have been smaller
-- and would have answered neither question: it forgets every run but the most recent, and it
-- records nothing at all for the ad-hoc query nobody saved, which is most of them.
--
-- Neither table stores a bucket, an endpoint, a region or a credential, for the same reason
-- analytics_dataset does not (V31): the connection ALIAS is stored and the bucket is read from
-- storage_connection at the moment the query is opened. A copy here would be a second source of
-- truth for it, and a connection later repointed at a different bucket would leave saved queries
-- and the history of what they read both pointing at the old one -- while the module's central
-- property, that a caller names a connection and never a bucket, quietly stopped being true of
-- everything on disk.

CREATE SEQUENCE IF NOT EXISTS analytics_query_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS analytics_query (
    analytics_query_id BIGINT       PRIMARY KEY,
    -- Nullable because a platform admin has no tenant of their own. As in analytics_dataset, a
    -- null here is NOT a row shared with every workspace: the entity filters on plain equality,
    -- which no null row satisfies, so a platform admin's saved queries stay visible to platform
    -- admins alone. storage_connection's "or tenant_id is null" form is deliberately not used --
    -- that one publishes the platform's rows to every tenant, which is right for a catalogue the
    -- whole application resolves buckets through and wrong for one person's saved work.
    tenant_id          BIGINT,
    query_name         VARCHAR(255) NOT NULL,
    -- The same two columns, spelled the same way, as analytics_dataset. A saved query names a
    -- location the way a saved dataset does rather than pointing at an analytics_dataset row,
    -- because most queries are written against a path the user never bothered to save as a
    -- dataset, and requiring one first would make an unrelated row a precondition for pressing
    -- Save. Carrying BOTH a dataset id and a path would be worse than either: one row with two
    -- ways to say where it reads from is one row that can disagree with itself.
    connection_alias   VARCHAR(255) NOT NULL,
    dataset_path       TEXT         NOT NULL,
    -- TEXT because a query is as long as somebody typed. Stored as written, never rewritten: the
    -- row ceiling the engine applies at execution belongs to the engine's configuration on the
    -- day it runs, and baking today's LIMIT into a saved query would freeze a limit the operator
    -- can still change.
    query_text         TEXT         NOT NULL,
    date_created       TIMESTAMP    NOT NULL DEFAULT now(),
    -- analytics_dataset has no date_updated because it has no update path at all. This row does:
    -- it is renamed and its SQL is edited, and updated_by on its own answers who without ever
    -- answering when.
    date_updated       TIMESTAMP,
    created_by         BIGINT,
    updated_by         BIGINT,
    CONSTRAINT fk_analytics_query_tenant     FOREIGN KEY (tenant_id)  REFERENCES tenant (tenant_id),
    CONSTRAINT fk_analytics_query_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id),
    CONSTRAINT fk_analytics_query_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (app_user_id)
);

-- The tenant filter is on every read of this table, and it is the only column it filters by.
CREATE INDEX IF NOT EXISTS idx_analytics_query_tenant_id ON analytics_query (tenant_id);

-- No unique constraint on (tenant_id, query_name). It would have to be that pair, and Postgres
-- treats nulls as distinct in a unique index, so the rule would bind every tenant and silently
-- not bind platform admins -- a constraint that applies to some callers and not others is harder
-- to explain than no constraint at all. Two people in a workspace each keeping "last night's
-- export" is also not a mistake worth refusing a save for.

CREATE SEQUENCE IF NOT EXISTS analytics_query_run_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS analytics_query_run (
    analytics_query_run_id BIGINT      PRIMARY KEY,
    tenant_id              BIGINT,
    -- Nullable on both sides of its life. Null on the way in for an ad-hoc query nobody saved,
    -- which is the common case and the one a per-query history column could not have recorded;
    -- and set null on the way out, so deleting a saved query removes the bookmark and not the
    -- record that somebody read that data. An audit row that a user can erase by tidying up
    -- their library is not an audit row.
    analytics_query_id     BIGINT,
    -- Copied onto the run rather than read back through analytics_query_id, so the row stays
    -- readable after the saved query is deleted, renamed or edited. This is the one place in the
    -- module where denormalising is right: everywhere else a copy risks drifting from the truth,
    -- but a history row IS the record of what was true at the time, and reading today's name and
    -- today's SQL back through a link would answer a question about last Tuesday with today's
    -- values. Still the alias and never the bucket -- what was read is named the same way here
    -- as everywhere else.
    connection_alias       VARCHAR(255) NOT NULL,
    dataset_path           TEXT         NOT NULL,
    -- The statement as the caller submitted it. Not the statement after the engine's LIMIT
    -- rewrite: the rewrite is reconstructable from the configuration, and the user's own text is
    -- reconstructable from nothing. It is also what makes a run re-runnable from history.
    query_text             TEXT         NOT NULL,
    -- SUCCESS, FAILED or REFUSED. REFUSED is the governor turning a caller away with no slot
    -- free -- it is recorded rather than dropped because a ceiling that is too low for the people
    -- using the module looks, from the logs alone, exactly like nobody using the module.
    run_status             VARCHAR(24)  NOT NULL,
    -- Null when there was no result: a failure and a refusal both read no rows, and zero is a
    -- real answer that they did not give.
    row_count              BIGINT,
    -- Wall clock inside the engine. Null on a refusal, which never reached it.
    duration_ms            BIGINT,
    -- The sentence the user was shown, which explain() has already mapped from the engine's own
    -- words. NEVER the raw engine string: those quote the statement back, and the statement
    -- carries the interpolated s3:// or azure:// location the whole module works to keep out of
    -- a response. A leak into a response is seen once; a leak into this column is kept.
    error_message          TEXT,
    date_created           TIMESTAMP    NOT NULL DEFAULT now(),
    created_by             BIGINT,
    -- Present for the same reason every audited table has it, and expected to stay null here: a
    -- run row is written once and never edited. That immutability is what makes it evidence.
    updated_by             BIGINT,
    CONSTRAINT fk_analytics_query_run_tenant     FOREIGN KEY (tenant_id)          REFERENCES tenant (tenant_id),
    CONSTRAINT fk_analytics_query_run_query      FOREIGN KEY (analytics_query_id) REFERENCES analytics_query (analytics_query_id) ON DELETE SET NULL,
    CONSTRAINT fk_analytics_query_run_created_by FOREIGN KEY (created_by)         REFERENCES app_user (app_user_id)
);

-- Both halves of every read of this table: the tenant filter, then newest first. The same index
-- is what a retention delete would need, which is the point below.
CREATE INDEX IF NOT EXISTS idx_analytics_query_run_tenant_date
    ON analytics_query_run (tenant_id, date_created DESC);

-- Postgres does not index a foreign key for you, and this one is scanned on every delete of a
-- saved query to apply the ON DELETE SET NULL above.
CREATE INDEX IF NOT EXISTS idx_analytics_query_run_query_id
    ON analytics_query_run (analytics_query_id);

-- NOTHING PRUNES THIS TABLE, and that is a decision rather than an omission.
--
-- A cap -- keep the last N per user, delete the rest on insert -- was the obvious shape and is
-- the wrong one for the first audit record this module has ever had. The question it exists to
-- answer is "who read that bucket, and when", asked weeks later by somebody who was not there,
-- and a table that silently discarded the answer at row N+1 would answer it wrongly while
-- looking like it had answered it. Losing rows is also the one thing that cannot be undone
-- later; adding a retention rule can be, in a changeset, over data that is still there.
--
-- What makes leaving it unbounded affordable is that a row is written by a person pressing Run.
-- The module has no scheduled query, no Kafka trigger and no API for a client to post history of
-- its own, so the table grows at human speed and the analytics governor caps even that at four
-- concurrent queries. The listing endpoint clamps its own window, so an unbounded table never
-- becomes an unbounded response.
--
-- The condition that changes this: phase four's completed-query events, or anything else that
-- lets a machine issue queries on a schedule. At that point a retention rule stops being
-- optional, and it should be written by whoever owns the audit question rather than guessed at
-- here by the phase that first writes the rows.

COMMENT ON TABLE analytics_query IS
    'A named, saved analytics query: a storage connection alias, a path inside it, and the SQL. The bucket is NOT stored -- it comes from the connection record at resolve time, so repointing a connection moves its saved queries with it.';
COMMENT ON TABLE analytics_query_run IS
    'One row per analytics query executed: what ran, against which connection alias and path, by whom, when, for how long, how many rows and whether it failed. The module''s "who read what and when" record. Holds no credential and no resolved bucket URL, and is never pruned -- see the changeset for why.';
COMMENT ON COLUMN analytics_query_run.analytics_query_id IS
    'The saved query this run came from, or null for an ad-hoc query. Set null when that saved query is deleted: the bookmark goes, the record that the data was read stays.';
COMMENT ON COLUMN analytics_query_run.error_message IS
    'The user-facing sentence explain() produced, never the raw engine string -- engine errors quote the statement back, and the statement carries the resolved object-store location.';
