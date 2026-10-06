-- Retry a failed run instead of handing it straight to a person.
--
-- Until now every failure was terminal. A Kafka broker that was briefly unreachable, a worker
-- that died mid-task, an object store that refused one connection -- each produced a run marked
-- Failed, an email, and a job that would not run again until its next slot came round or somebody
-- pressed the button. The overwhelming majority of those are transient and would have succeeded
-- on a second attempt seconds later.
--
-- The defaults here reproduce TODAY'S BEHAVIOUR EXACTLY. max_attempts = 1 means one attempt and
-- no retry, which is what every existing job gets, so applying this changeset changes nothing
-- about how anything runs until somebody raises the number on a job they choose. That is
-- deliberate: a migration that silently starts retrying every job on every database would change
-- the failure behaviour of jobs nobody has looked at, including ones where a second attempt is
-- actively harmful (a task that appends to a file, or charges something).
--
-- Why the retry re-uses the queue row rather than inserting a new one: the dispatcher decides
-- whether a job is busy by counting its rows in Queue, Start or Running, and that count is what
-- stops the same job running twice at once. A retry is the SAME slot's work being attempted
-- again, not new work, so it must continue to occupy exactly one row. Inserting a second row per
-- attempt would also mean every failure reaching Failed at least once, which is precisely what
-- sends the premature email this feature exists to avoid. Per-attempt history is not lost -- each
-- attempt writes its own reason to job_audit_logs, which is where a run's narrative already
-- lives.

ALTER TABLE job_queue ADD COLUMN IF NOT EXISTS attempt INTEGER NOT NULL DEFAULT 1;

-- NULL means "not waiting on anything" and is the state of every ordinary run. A non-null value
-- is a promise the dispatcher keeps: this row is in Queue, but must not be picked up before then.
-- Written in the application's own clock (America/Chicago, pinned in ModelApplication.main), NOT
-- by a database default of now(), because the database's now() is UTC and the two are five hours
-- apart -- a backoff compared against the wrong one of those is either instant or five hours long.
ALTER TABLE job_queue ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;

COMMENT ON COLUMN job_queue.attempt IS
    'Which attempt this run is, starting at 1. Greater than 1 means an earlier attempt of this same slot failed and was retried; the reasons are in job_audit_logs.';

COMMENT ON COLUMN job_queue.next_attempt_at IS
    'When a run awaiting retry becomes eligible for dispatch, in the application timezone. NULL for any run not waiting on a backoff, which is the normal case.';

-- Partial index: the dispatcher's pick-up query filters on this column every minute, and only a
-- vanishing fraction of rows are ever non-null, so indexing the whole column would be mostly
-- empty entries maintained on every insert for no read benefit.
CREATE INDEX IF NOT EXISTS idx_job_queue_next_attempt_at
    ON job_queue (next_attempt_at)
    WHERE next_attempt_at IS NOT NULL;

-- Per job, so that a fragile network-bound job can be given three attempts while a job that must
-- not run twice keeps one. Not a global setting for the same reason.
ALTER TABLE source_job ADD COLUMN IF NOT EXISTS max_attempts INTEGER NOT NULL DEFAULT 1;

ALTER TABLE source_job ADD COLUMN IF NOT EXISTS retry_backoff_seconds INTEGER NOT NULL DEFAULT 60;

COMMENT ON COLUMN source_job.max_attempts IS
    'Total attempts a run of this job may make, including the first. 1 (the default) disables retry and is the behaviour every job had before this column existed.';

COMMENT ON COLUMN source_job.retry_backoff_seconds IS
    'Base delay before retrying a failed run. The wait doubles with each attempt, so 60 gives 60s then 120s then 240s.';

-- Guard rails on values a form could otherwise send. An unbounded max_attempts turns a job that
-- fails deterministically into an infinite retry loop that occupies its own slot for ever -- the
-- busy-count means such a job never runs its real schedule again, so the ceiling is what stops a
-- typo taking a job permanently off the air.
ALTER TABLE source_job DROP CONSTRAINT IF EXISTS ck_source_job_max_attempts;
ALTER TABLE source_job ADD CONSTRAINT ck_source_job_max_attempts
    CHECK (max_attempts >= 1 AND max_attempts <= 10);

ALTER TABLE source_job DROP CONSTRAINT IF EXISTS ck_source_job_retry_backoff_seconds;
ALTER TABLE source_job ADD CONSTRAINT ck_source_job_retry_backoff_seconds
    CHECK (retry_backoff_seconds >= 1 AND retry_backoff_seconds <= 3600);
