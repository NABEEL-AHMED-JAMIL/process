-- Same intent as V12 (tenant/user FKs) but for the remaining loose job-graph columns:
-- job_queue.job_id, scheduler.job_id (both -> source_job.job_id), and
-- job_audit_logs.job_queue_id (-> job_queue.job_queue_id). Zero orphaned rows confirmed
-- across all three before writing this migration. No ON DELETE clause (defaults to
-- NO ACTION) -- SourceJob/JobQueue rows are never hard-deleted by this app either
-- (Status.Delete soft-delete, same as tenant/app_user).

ALTER TABLE job_queue ADD CONSTRAINT fk_job_queue_source_job FOREIGN KEY (job_id) REFERENCES source_job(job_id);
ALTER TABLE scheduler ADD CONSTRAINT fk_scheduler_source_job FOREIGN KEY (job_id) REFERENCES source_job(job_id);
ALTER TABLE job_audit_logs ADD CONSTRAINT fk_job_audit_logs_job_queue FOREIGN KEY (job_queue_id) REFERENCES job_queue(job_queue_id);
