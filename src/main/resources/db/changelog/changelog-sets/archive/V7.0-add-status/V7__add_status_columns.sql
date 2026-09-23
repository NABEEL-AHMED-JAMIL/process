-- Add status column to job_queue and job_audit_logs
-- This change adds a new varchar status column and sets default to 'Active'.
-- It also updates existing rows to 'Active' when NULL.

ALTER TABLE job_queue ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'Active';
-- Ensure no nulls
UPDATE job_queue SET status = 'Active' WHERE status IS NULL;
ALTER TABLE job_queue ALTER COLUMN status SET NOT NULL;

ALTER TABLE job_audit_logs ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'Active';
-- Ensure no nulls
UPDATE job_audit_logs SET status = 'Active' WHERE status IS NULL;
ALTER TABLE job_audit_logs ALTER COLUMN status SET NOT NULL;

-- Optionally, ensure existing job_queue rows for deleted jobs keep their status as appropriate
-- (No further action here; app logic will update job_queue.status when needed.)

