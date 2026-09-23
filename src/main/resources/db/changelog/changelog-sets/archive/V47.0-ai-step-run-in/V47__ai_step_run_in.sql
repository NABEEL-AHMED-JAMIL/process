-- Where an AI step runs: on the server before dispatch (the default), or in the worker, which
-- can read a file the task names and asks the server to run the prompt through aiPrompt.json/run.
ALTER TABLE pipeline_field ADD COLUMN IF NOT EXISTS run_in VARCHAR(16);
UPDATE pipeline_field SET run_in = 'server' WHERE field_type = 'ai' AND run_in IS NULL;
COMMENT ON COLUMN pipeline_field.run_in IS 'field_type = ai: server (before dispatch) | worker (the consumer runs it via aiPrompt.json/run)';
