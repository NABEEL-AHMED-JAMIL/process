-- A callback token per run, instead of one shared secret for every worker.
--
-- The worker reports a run's progress on /changeState, /addLogs and /addLogsBatch, and until
-- now proved it was a worker with WORKER_CALLBACK_TOKEN: one value in every worker's
-- environment, good for every run in every tenant, for ever. Anyone holding it could mark any
-- run Failed or append logs to it.
--
-- Now each dispatch mints a random token for that run, keeps only its SHA-256 here, and sends
-- the token to the worker inside the run's own message. The worker echoes it on that run's
-- callbacks and the server checks the hash -- so a token is good for exactly one run, until the
-- run ends or the budget below runs out, and there is nothing to distribute or rotate. A retry
-- re-uses the job_queue row (V38) and mints afresh, so an earlier attempt's token stops working
-- the moment the next attempt is dispatched.
--
-- All three columns are nullable: a run never dispatched, or dispatched before this change,
-- carries nothing, and NotifyResetApi lets such a run report through the legacy shared secret
-- while that is still configured.

ALTER TABLE job_queue ADD COLUMN IF NOT EXISTS callback_token_hash       VARCHAR(64);
ALTER TABLE job_queue ADD COLUMN IF NOT EXISTS callback_token_attempt    INTEGER;
ALTER TABLE job_queue ADD COLUMN IF NOT EXISTS callback_token_expires_at TIMESTAMP;

COMMENT ON COLUMN job_queue.callback_token_hash IS
    'SHA-256 (hex) of the token issued to the worker for this run''s callbacks; null once the run ends or if it was never dispatched.';
COMMENT ON COLUMN job_queue.callback_token_attempt IS
    'The attempt the current callback token was minted for.';
COMMENT ON COLUMN job_queue.callback_token_expires_at IS
    'When the current callback token stops being accepted, whatever the run''s state.';
