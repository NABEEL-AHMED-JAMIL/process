-- One in-flight run per job, enforced by the database (P12, MIG-113, MIG-135).
--
-- The in-flight set is Queue, Start and Running -- exactly JobStatus.IN_FLIGHT, and exactly what
-- getCountForInQueueJobByJobId and findStalledRuns name. JobStatusInFlightTest reads the predicate
-- below and holds it to that set: a mismatch between this index and the Java set reintroduces the
-- race in a harder-to-see form. UPPER(), as every native read of job_status does, so a status
-- written in another case is still in flight.
--
-- First, any job that already has more than one run in flight keeps ONE: the most advanced
-- (Running, then Start, then Queue), the newest among equals. Every other is closed as Interrupt --
-- the stall sweep's verdict, never Failed or Completed, because nobody knows what it did. end_time
-- is written in the application's wall clock (America/Chicago), which is how every other writer of
-- this column writes it -- the database's own clock is UTC.
WITH ranked AS (
    SELECT job_queue_id,
           row_number() OVER (
               PARTITION BY job_id
               ORDER BY CASE UPPER(job_status) WHEN 'RUNNING' THEN 0 WHEN 'START' THEN 1 ELSE 2 END,
                        job_queue_id DESC) AS keep_rank
    FROM job_queue
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING')
)
UPDATE job_queue q
SET job_status = 'Interrupt',
    end_time = COALESCE(q.end_time, (now() AT TIME ZONE 'America/Chicago')),
    job_status_message = 'Closed when one in-flight run per job became a database rule (V83): another run of this job was also in flight.'
FROM ranked r
WHERE r.job_queue_id = q.job_queue_id
  AND r.keep_rank > 1;

CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job ON job_queue (job_id)
    WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING');
