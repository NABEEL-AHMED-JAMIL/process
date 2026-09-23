-- What a read of a dataset actually cost, on this deployment, written down.
--
-- The module has been telling users "Try a narrower dataset, or Parquet instead of CSV" since
-- phase one (AnalyticsQueryService.explain), and until this table nobody had checked whether that
-- advice is true here. A benchmark whose result lives in somebody's memory, or in a pull request
-- comment, is a comparison against a number that will have been misremembered by the time it is
-- next needed -- which is the whole reason the measurement is persisted rather than logged.
--
-- MOST OF THE COLUMNS BELOW EXIST TO STOP THE NUMBER BEING MISREAD, AND THAT IS THE POINT OF THE
-- TABLE. A row saying "CSV: 412 ms" is worse than no row at all: 412 ms of what? One query, or a
-- whole file open? One run, or the average of five? Cold, or after the JIT had warmed up? Against
-- how many rows, how many columns and how many bytes? With the row ceiling set where it is today,
-- or where it was in September? Every one of those questions has a column here, because the person
-- who has to answer them is the same person who wrote the row, three months later, having
-- forgotten.
--
-- The confound this table was designed around is gap 17: a file open in the Studio costs THREE
-- governed sessions -- the schema read, the row count and the first page -- so a measurement of
-- "opening a dataset" contains the per-session cost three times, while a measurement of one query
-- contains it once and is not what a user experiences. Neither is the wrong choice; recording
-- which one was made is the only wrong thing to skip. measure_kind, measured_what and
-- sessions_per_run all say it, in three registers: a machine-readable name, a sentence, and a
-- number.
--
-- What this table CANNOT record, and must not be read as recording. The module's premise is two
-- claims: that reading in place beats loading into Postgres, and that Parquet beats CSV. Only the
-- second is measurable here. There is no load-into-Postgres path in this application -- adding one
-- is explicitly out of scope -- so the first claim has nothing to time against, and a row in this
-- table is evidence about FORMATS and about SESSION COST. It is not evidence about the
-- architecture.

CREATE SEQUENCE IF NOT EXISTS analytics_benchmark_result_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS analytics_benchmark_result (
    analytics_benchmark_result_id BIGINT       PRIMARY KEY,
    -- Nullable because a platform admin has no tenant of their own, and as things stand today
    -- EVERY row here will have a null in it: the endpoint that writes these is PLATFORM_ADMIN
    -- only, because running a benchmark is a deliberate load generator aimed at a governor with
    -- four slots in it.
    --
    -- The column and the entity's filter are here anyway, and not as ceremony. Same plain equality
    -- as analytics_dataset and analytics_query, deliberately NOT storage_connection's
    -- "(tenant_id = :tenantId or tenant_id is null)": that form publishes the platform's rows to
    -- every tenant, and a benchmark row names a connection alias and a path inside it -- which is
    -- to say, where one workspace keeps its data. If the role floor is ever lowered to let a
    -- tenant admin measure their own datasets, the rows land scoped from the first one, instead of
    -- a migration having to go back and guess who owned what.
    tenant_id                     BIGINT,
    -- One invocation of the harness. The rows that are MEANT to be compared with each other share
    -- this, because a comparison is only sound between numbers taken minutes apart on the same
    -- JVM against the same object store -- not between a CSV row from September and a Parquet row
    -- from November, which is exactly the comparison a label alone would invite.
    batch_id                      VARCHAR(64)  NOT NULL,
    -- The operator's name for what is being compared: "orders-1m", "orders-50m". This is how more
    -- than one SIZE is recorded -- the harness measures the datasets it is given, and the label is
    -- what says which rung of the ladder they are.
    benchmark_label               VARCHAR(255) NOT NULL,
    -- FILE_OPEN or QUERY. See the header: this is half of the answer to "412 ms of what?".
    measure_kind                  VARCHAR(24)  NOT NULL,
    -- The other half, in words, and denormalised on purpose. The enum name above can be renamed,
    -- extended or reinterpreted by a later phase; the sentence recorded on the day says what this
    -- particular number timed and stays true whatever happens to the vocabulary around it. A
    -- reader of one row should never have to find the code that wrote it.
    measured_what                 TEXT         NOT NULL,
    -- The confound as an integer: how many governed sessions ONE measured run cost. 3 for a file
    -- open (schema, count, first page), 1 for a single query. Nothing derives anything from this
    -- column -- it exists so that a reader comparing a 3-session number with a 1-session number
    -- can see that is what they are doing.
    sessions_per_run              INTEGER      NOT NULL,
    -- The alias and the path, spelled exactly as analytics_dataset and analytics_query spell them,
    -- and for the same reason: no bucket, no endpoint, no region, no credential. What was measured
    -- is named the way everything else in this module names it, so a connection later repointed at
    -- a different bucket does not leave this table asserting where the bytes were.
    connection_alias              VARCHAR(255) NOT NULL,
    dataset_path                  TEXT         NOT NULL,
    -- CSV, TSV, JSON or PARQUET, as DatasetResolver derived it from the path at measurement time.
    -- The whole comparison turns on this column, which is why it is not nullable and is recorded
    -- from the resolver rather than from anything the caller said.
    dataset_format                VARCHAR(24)  NOT NULL,
    -- The statement, for a QUERY measurement, exactly as it was submitted -- before bounded()
    -- wrapped it. Null for a file open, which runs no statement anybody wrote.
    --
    -- The harness has no default SQL and requires this, which looks unhelpful until you write the
    -- obvious default down: "SELECT count(*) FROM dataset" is answered from Parquet's footer
    -- without reading a single row, and from every byte of a CSV. A harness defaulting to it would
    -- manufacture a spectacular Parquet win that says nothing about reading data, and would do so
    -- silently, in the one tool built to check that claim. So the statement is the operator's, and
    -- it is kept here, because a duration without the statement beside it is not a measurement.
    query_text                    TEXT,
    -- The dataset's shape, measured rather than declared: the harness reads the schema and counts
    -- the rows itself, OUTSIDE the timed sample, and records what it found. A benchmark that took
    -- these from the request would be a benchmark whose most explanatory columns were hearsay.
    row_count                     BIGINT,
    column_count                  INTEGER,
    -- Bytes on the object store, and the reason it matters more than it looks: Parquet's advantage
    -- is mostly compression, so a duration without a size cannot tell you whether a format won or
    -- whether one file simply had less of the data in it.
    --
    -- Null when it could not be established honestly -- a multi-file dataset (the path is a glob,
    -- and there is no one object to ask about), or an object store that would not answer. Null
    -- means "not known", never "zero".
    dataset_bytes                 BIGINT,
    -- How many runs were thrown away before the timer was believed, and how many were kept.
    --
    -- Warmups are discarded because the first execution of this path in a JVM measures things that
    -- have nothing to do with the dataset: HotSpot still interpreting the JDBC and result-marshal
    -- code, DuckDB's httpfs extension loading into the process, and the object-store client
    -- opening its first TLS connection. Recorded rather than assumed, and allowed to be zero,
    -- because "we discarded the cold run" and "we did not" produce different numbers and the row
    -- has to say which one it is.
    warmup_runs                   INTEGER      NOT NULL,
    measured_runs                 INTEGER      NOT NULL,
    -- The spread, not just the middle. A single number from a single run of a JIT-compiled JVM
    -- against a network object store is noise wearing the costume of a measurement, and min/max
    -- are what let a reader see that: two runs 40 ms apart mean something, two runs 4000 ms apart
    -- mean the network was busy and the median is the only figure worth quoting.
    --
    -- The median is the headline on purpose. One slow run drags a mean of five a long way, and the
    -- runs that go slow here go slow for reasons that are not about the file.
    min_ms                        BIGINT       NOT NULL,
    median_ms                     BIGINT       NOT NULL,
    max_ms                        BIGINT       NOT NULL,
    mean_ms                       BIGINT       NOT NULL,
    -- Every kept run, comma separated, in the order they ran. The four aggregates above can all be
    -- recomputed from this, which is the point: a reader who distrusts the summary -- and they
    -- should, summaries are where measurements go to become slogans -- can look at the samples.
    -- Ordered rather than sorted so a run that got steadily faster is visible as such.
    run_durations_ms              TEXT         NOT NULL,
    -- The limits in force when this ran, as a compact string: max-rows, preview page size, query
    -- timeout, concurrency ceiling. A QUERY measurement stops at the row ceiling, so a row
    -- measured under max-rows=1000 is not comparable with one measured under max-rows=100000 --
    -- and the configuration is not recoverable from anything else once somebody edits a properties
    -- file. Kept as one string rather than four columns because nothing queries on it; it is read
    -- by a person who is deciding whether two rows may be compared at all.
    limits_at_run                 VARCHAR(255) NOT NULL,
    date_created                  TIMESTAMP    NOT NULL DEFAULT now(),
    created_by                    BIGINT,
    -- Present because every audited table here has it, and expected to stay null: a measurement is
    -- written once and is not somebody's to revise afterwards. The same reading analytics_query_run
    -- takes of its own updated_by, for the same reason -- a number that can be edited after the
    -- fact is not evidence.
    updated_by                    BIGINT,
    CONSTRAINT fk_analytics_benchmark_result_tenant     FOREIGN KEY (tenant_id)  REFERENCES tenant (tenant_id),
    CONSTRAINT fk_analytics_benchmark_result_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id),
    CONSTRAINT fk_analytics_benchmark_result_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (app_user_id)
);

-- Both halves of the default read of this table: the tenant filter, then newest first.
CREATE INDEX IF NOT EXISTS idx_analytics_benchmark_result_tenant_date
    ON analytics_benchmark_result (tenant_id, date_created DESC);

-- The two ways a comparison is actually pulled back out: "the rows from that run" and "every
-- measurement labelled orders-1m". Both are the reads the fetch endpoint issues.
CREATE INDEX IF NOT EXISTS idx_analytics_benchmark_result_batch
    ON analytics_benchmark_result (batch_id);

CREATE INDEX IF NOT EXISTS idx_analytics_benchmark_result_label
    ON analytics_benchmark_result (benchmark_label);

-- Nothing prunes this table either, and here it costs less than it does for analytics_query_run.
-- A row is written only when a platform administrator deliberately generates load, at most a
-- handful per invocation, and the reads are clamped windows. The table grows at the speed of
-- somebody choosing to run a benchmark.

COMMENT ON TABLE analytics_benchmark_result IS
    'One measured read of one dataset: what was measured, of what shape, how many times, and the spread -- not just a duration. Written only by the PLATFORM_ADMIN benchmark endpoint. Records format-versus-format and session cost; records nothing about reading in place versus loading into Postgres, which this application has no code path for.';
COMMENT ON COLUMN analytics_benchmark_result.measure_kind IS
    'FILE_OPEN (schema + count + first page, three governed sessions) or QUERY (one statement, one session). Which was chosen changes the number by more than the format does -- see sessions_per_run.';
COMMENT ON COLUMN analytics_benchmark_result.sessions_per_run IS
    'How many governed DuckDB sessions one measured run cost. A file open costs three, so that number carries the per-session cost three times; a query costs one. Recorded so the two are never silently compared.';
COMMENT ON COLUMN analytics_benchmark_result.run_durations_ms IS
    'Every kept run in the order it ran, comma separated. The summary columns are recomputable from this; it is here so a reader can distrust the summary.';
COMMENT ON COLUMN analytics_benchmark_result.dataset_bytes IS
    'Size on the object store, or null when it could not be established -- a glob names no single object. Null is "not known", never zero. Parquet''s advantage is largely compression, so a duration without this cannot say whether the format won or the file was simply smaller.';
COMMENT ON COLUMN analytics_benchmark_result.limits_at_run IS
    'The analytics limits in force when this was measured. A query result stops at the row ceiling, so two rows measured under different ceilings are not comparable, and the ceiling is not recoverable from anything else afterwards.';
