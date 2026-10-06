-- Who made each thing, and who last touched it.
--
-- Five tables carried created_by/updated_by already; every other user-editable entity had no
-- author at all, so screens could show a job or a connection with no way to tell where it came
-- from. These are the tables behind screens somebody creates or edits things on. Purely
-- operational records (job_queue, job_audit_logs, notification) are left alone: they are written
-- by the engine, not by a person.
--
-- Nullable on purpose. Every row that already exists predates the column and genuinely has no
-- known author, and inventing one would be worse than showing nothing.

ALTER TABLE source_job                ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE source_job                ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE source_task               ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE source_task               ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE source_task_type          ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE source_task_type          ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE lookup_data               ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE lookup_data               ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE dynamic_form              ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE dynamic_form              ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE storage_connection        ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE storage_connection        ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE kafka_connection_profile  ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE kafka_connection_profile  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE ai_agent                  ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE ai_agent                  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE tenant                    ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE tenant                    ADD COLUMN IF NOT EXISTS updated_by BIGINT;

ALTER TABLE app_user                  ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE app_user                  ADD COLUMN IF NOT EXISTS updated_by BIGINT;

-- task_form already records its author; it just never recorded an editor.
ALTER TABLE task_form                 ADD COLUMN IF NOT EXISTS updated_by BIGINT;

-- query_execution is a record of a run rather than a thing that gets edited, so it keeps
-- created_by alone.
