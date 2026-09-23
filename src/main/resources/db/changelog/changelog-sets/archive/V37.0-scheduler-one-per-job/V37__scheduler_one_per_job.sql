-- One scheduler row per job, enforced by the database.
--
-- The code has always assumed this and never said so. SourceJobServiceImpl reads a job's timetable
-- with findSchedulerByJobId, which returns a single row, so a second row for one job turns every
-- by-id read, update and skip of that job into a 500 -- and not only there: DashboardServiceImpl
-- and JobAssistantServiceImpl call the same finder. The failure is total for that job and says
-- nothing useful, because an unexpected exception is exactly what a 500 is for.
--
-- The write paths now refuse to create a second row, which stops the trap being SET. This is the
-- other half: a constraint means it cannot be set by a path nobody has thought of -- a bulk
-- import, a future endpoint, a hand-written UPDATE during an incident -- and it documents the
-- assumption where the next person to add a writer will actually meet it.
--
-- <b>This changeset will REFUSE TO APPLY where duplicates already exist, and that is deliberate.</b>
-- There were none in the environment this was written against:
--
--     select job_id, count(*) from scheduler group by job_id having count(*) > 1;   -- 0 rows
--
-- If it fails on another database, that database has jobs that are already broken -- every by-id
-- read of them is a 500 today -- and the right response is to look at the duplicates and decide
-- which timetable is the real one, not to drop the constraint. A silent "keep the newest" written
-- here would pick one at random for somebody else's schedule.
--
-- NULL job_id is left alone: Postgres treats nulls as distinct in a unique index, so a scheduler
-- row not yet attached to a job is unaffected, which is the behaviour the insert path relies on.

ALTER TABLE scheduler
    DROP CONSTRAINT IF EXISTS uk_scheduler_job_id;

ALTER TABLE scheduler
    ADD CONSTRAINT uk_scheduler_job_id UNIQUE (job_id);

COMMENT ON CONSTRAINT uk_scheduler_job_id ON scheduler IS
    'A job has at most one timetable. findSchedulerByJobId returns a single row, so a second row turns every by-id read of that job into a 500.';
