-- MIG-333's way back: the triggers first (a run's next status change must not write into a table that is gone), then the
-- journal. Events already relayed stay in platform_outbox and on the topic.
DROP TRIGGER IF EXISTS trg_api_event_out_submission ON public.form_submission;
DROP TRIGGER IF EXISTS trg_api_event_out_file_made ON public.run_output;
DROP TRIGGER IF EXISTS trg_api_event_out_run_status ON public.job_queue;
DROP FUNCTION IF EXISTS public.api_event_out_submission();
DROP FUNCTION IF EXISTS public.api_event_out_file_made();
DROP FUNCTION IF EXISTS public.api_event_out_run_status();
DROP TABLE IF EXISTS public.api_event_out;
DROP SEQUENCE IF EXISTS public.api_event_out_seq;
