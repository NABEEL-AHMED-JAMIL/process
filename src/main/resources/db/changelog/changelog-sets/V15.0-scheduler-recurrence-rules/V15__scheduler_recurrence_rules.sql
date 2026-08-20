-- Scheduler redesign (see design doc reviewed with the user before this migration):
--  - recurrence -> interval_value: same column, renamed to match what it actually is
--    ("repeat every N of the frequency unit"), not touched otherwise.
--  - days_of_week / day_of_month: new, nullable, opt-in. Only read for Weekly/Monthly jobs
--    that use the new day-picker; every existing schedule leaves these null and keeps
--    firing exactly as before via the plain interval math.
--  - next_run_at replaces recurrence_time as the one authoritative "when does this fire
--    next" field, now indexed -- mirrors query_schedule.next_run_at, which already proved
--    this pattern out for the Query Engine feature.
--  - expired: set true the moment a recurring job passes its end date, instead of the old
--    behavior of silently leaving recurrence_time frozen in the past with no signal anywhere.

ALTER TABLE scheduler RENAME COLUMN recurrence TO interval_value;
ALTER TABLE scheduler ADD COLUMN days_of_week VARCHAR(30);
ALTER TABLE scheduler ADD COLUMN day_of_month SMALLINT;
ALTER TABLE scheduler ADD COLUMN next_run_at TIMESTAMP;
ALTER TABLE scheduler ADD COLUMN expired BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE scheduler ADD COLUMN date_updated TIMESTAMP;

UPDATE scheduler SET next_run_at = recurrence_time WHERE recurrence_time IS NOT NULL;
UPDATE scheduler SET next_run_at = start_date::timestamp + start_time WHERE next_run_at IS NULL;

CREATE INDEX idx_scheduler_next_run_at ON scheduler (next_run_at);

ALTER TABLE scheduler DROP COLUMN recurrence_time;
