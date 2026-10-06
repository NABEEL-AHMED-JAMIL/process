-- Indexes for foreign keys that carry real query paths. Postgres indexes a primary key but
-- never the referencing side, so each of these was a sequential scan on a join the app runs
-- routinely. The data here is small enough that none of them is slow yet -- these are for
-- the shape of the query, not for a measured regression.

-- listSourceJob calls findByJobIdIn for every job on every load, and the run-history screen
-- looks a scheduler up by job.
CREATE INDEX IF NOT EXISTS idx_scheduler_job_id ON scheduler (job_id);

-- Deleting a task cascades to its jobs through this column.
CREATE INDEX IF NOT EXISTS idx_source_job_task_detail_id ON source_job (task_detail_id);

-- The task list resolves each task's type, and the type screen lists its linked tasks.
CREATE INDEX IF NOT EXISTS idx_source_task_type_id ON source_task (source_task_type_id);

-- A task's payload rows are read whenever the task is opened.
CREATE INDEX IF NOT EXISTS idx_source_task_payload_task ON source_task_payload (payload_id);

-- The lookup screen loads children per parent and is tenant-filtered.
CREATE INDEX IF NOT EXISTS idx_lookup_data_parent ON lookup_data (parent_lookup_id);
CREATE INDEX IF NOT EXISTS idx_lookup_data_tenant_id ON lookup_data (tenant_id);

-- Notifications are read per tenant and per recipient, newest first.
CREATE INDEX IF NOT EXISTS idx_notification_tenant_id ON notification (tenant_id);
CREATE INDEX IF NOT EXISTS idx_notification_recipient ON notification (recipient_user_id, date_created DESC);
