-- MIG-225: Core's part of the round-trip and execution schema (feature plan 21.3) -- what a run did step by step
-- (step_execution), the datasets its steps wrote (run_dataset), and the results it produced with their reviews
-- (result_record, result_review). Expand-only: four new tables; job_queue, source_task, source_job and the JobStatus
-- lifecycle are untouched.
--
-- Why these are Core's: 21.1 gives Core Orchestration "pipelines, schedules, engine, executions, review", and 21.2
-- puts the review on the Executions resource (console) and on /v1/executions/{id}/review (customer). A result and its
-- reviews stay together, so result_record is here with result_review. The other MIG-225 tables are in the database
-- of the service that owns them: data_contract, contract_version, api_call_log and data_policy in integration_db,
-- stored_file in storage_db, ai_prompt_tool and ai_step_model_option in ai_db, tenant.management_mode in identity_db.
-- intake_batch, intake_record, result_manifest, api_client, destination and delivery are deferred with the customer
-- integration (owner, 2026-09-28).
--
-- The rules of process's own tables: tenant_id NOT NULL on every row, held to the parent's by a composite foreign key
-- onto its (id, tenant_id) and filled from the parent when a writer leaves it out (V102's tenant_id_from_parent);
-- times are instants (timestamptz, V100); a status column takes only its enum's spellings (V164); sequences start at
-- 1000 (MIG-32). Ids of other services' rows are plain bigints: another database, so no foreign key.

-- ----------------------------------------------------------------------------------------------- step_execution
CREATE SEQUENCE public.step_execution_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.step_execution (
    step_execution_id bigint DEFAULT nextval('public.step_execution_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_queue_id bigint NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    step_index integer NOT NULL,
    task_code character varying(128) NOT NULL,
    status character varying(16) DEFAULT 'Queue' NOT NULL,
    started_at timestamp with time zone,
    ended_at timestamp with time zone,
    records_in bigint,
    records_out bigint,
    error jsonb,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_step_execution PRIMARY KEY (step_execution_id),
    CONSTRAINT ux_step_execution_id_tenant UNIQUE (step_execution_id, tenant_id),
    -- One row per step of each attempt: a retried run (job_queue.attempt) records its steps again.
    CONSTRAINT ux_step_execution_run_step UNIQUE (job_queue_id, attempt, step_index),
    -- A step is part of its run: in the run's workspace, and gone with the run.
    CONSTRAINT fk_step_execution_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_step_execution_status_enum CHECK (status IN ('Queue', 'Start', 'Running', 'Failed', 'Completed', 'Skip',
        'Interrupt', 'Missed')),
    CONSTRAINT ck_step_execution_attempt CHECK (attempt > 0),
    CONSTRAINT ck_step_execution_step_index CHECK (step_index >= 0),
    CONSTRAINT ck_step_execution_records CHECK ((records_in IS NULL OR records_in >= 0) AND (records_out IS NULL OR records_out >= 0)),
    CONSTRAINT ck_step_execution_times CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
);

COMMENT ON TABLE public.step_execution IS 'One step of one run (job_queue): its task, status (the JobStatus values), times, records in and out, and the error. MIG-225.';
COMMENT ON COLUMN public.step_execution.status IS 'The run''s own vocabulary, process.model.enums.JobStatus: a step adds nothing to the lifecycle.';

CREATE INDEX idx_step_execution_tenant ON public.step_execution (tenant_id);

CREATE TRIGGER step_execution_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.step_execution
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- -------------------------------------------------------------------------------------------------- run_dataset
CREATE SEQUENCE public.run_dataset_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.run_dataset (
    run_dataset_id bigint DEFAULT nextval('public.run_dataset_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    step_execution_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    storage_key character varying(1024) NOT NULL,
    row_count bigint,
    columns jsonb,
    expires_at timestamp with time zone,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_run_dataset PRIMARY KEY (run_dataset_id),
    CONSTRAINT ux_run_dataset_step_name UNIQUE (step_execution_id, name),
    CONSTRAINT fk_run_dataset_step_tenant FOREIGN KEY (step_execution_id, tenant_id)
        REFERENCES public.step_execution (step_execution_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_run_dataset_row_count CHECK (row_count IS NULL OR row_count >= 0),
    CONSTRAINT ck_run_dataset_columns CHECK (columns IS NULL OR jsonb_typeof(columns) = 'array')
);

COMMENT ON TABLE public.run_dataset IS 'A dataset a step wrote to the platform bucket (datasets/{execution_id}/{step}/{name}.json): its key, rows, columns and expiry. The AI is handed this reference, never the rows. MIG-225.';
COMMENT ON COLUMN public.run_dataset.storage_key IS 'The object key inside the platform; never shown to a customer.';

CREATE INDEX idx_run_dataset_tenant ON public.run_dataset (tenant_id);
CREATE INDEX idx_run_dataset_expires_at ON public.run_dataset (expires_at) WHERE expires_at IS NOT NULL;

CREATE TRIGGER run_dataset_tenant_id_from_parent BEFORE INSERT OR UPDATE OF step_execution_id ON public.run_dataset
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('step_execution', 'step_execution_id', 'step_execution_id');

-- ------------------------------------------------------------------------------------------------ result_record
CREATE SEQUENCE public.result_record_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.result_record (
    result_record_id bigint DEFAULT nextval('public.result_record_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    job_queue_id bigint NOT NULL,
    intake_record_id bigint,
    contract_version_id bigint,
    subject_ref character varying(128),
    result jsonb NOT NULL,
    review_status character varying(16) DEFAULT 'PENDING' NOT NULL,
    model_connection_id bigint,
    prompt_id bigint,
    prompt_version integer,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_result_record PRIMARY KEY (result_record_id),
    CONSTRAINT ux_result_record_id_tenant UNIQUE (result_record_id, tenant_id),
    -- A result is kept when nothing else is: no ON DELETE, so a run with results is not deleted around them.
    CONSTRAINT fk_result_record_run_tenant FOREIGN KEY (job_queue_id, tenant_id)
        REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE,
    CONSTRAINT ck_result_record_review_status_enum CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_result_record_result_object CHECK (jsonb_typeof(result) = 'object'),
    CONSTRAINT ck_result_record_prompt_version CHECK (prompt_version IS NULL OR (prompt_version > 0 AND prompt_id IS NOT NULL))
);

COMMENT ON TABLE public.result_record IS 'One result a run produced (result.json''s object), the contract version it was checked against, the model connection and prompt version that made it, and its review status. Starts PENDING: every result is a draft until reviewed (MIG-221). MIG-225.';
COMMENT ON COLUMN public.result_record.subject_ref IS 'Keyed hash of the case''s subject (e.g. a patient id), never the identifier itself; the history is read by (tenant_id, subject_ref, date_created).';
COMMENT ON COLUMN public.result_record.intake_record_id IS 'integration_db''s intake_record (deferred with the customer intake, MIG-235), a plain id; null for a run with no intake.';
COMMENT ON COLUMN public.result_record.contract_version_id IS 'integration_db''s contract_version (the OUT contract), a plain id: another database.';
COMMENT ON COLUMN public.result_record.model_connection_id IS 'ai_db''s ai_model_connection, a plain id: another database.';
COMMENT ON COLUMN public.result_record.prompt_id IS 'ai_db''s ai_prompt, a plain id; prompt_version is the version the run pinned.';

CREATE INDEX idx_result_record_run ON public.result_record (job_queue_id);
CREATE INDEX idx_result_record_subject_history ON public.result_record (tenant_id, subject_ref, date_created) WHERE subject_ref IS NOT NULL;

CREATE TRIGGER result_record_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.result_record
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');

-- ------------------------------------------------------------------------------------------------ result_review
CREATE SEQUENCE public.result_review_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE public.result_review (
    result_review_id bigint DEFAULT nextval('public.result_review_seq'::regclass) NOT NULL,
    tenant_id bigint NOT NULL,
    result_record_id bigint NOT NULL,
    party character varying(16) NOT NULL,
    reviewer character varying(255) NOT NULL,
    decision character varying(16) NOT NULL,
    comment text,
    reviewed_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint,
    date_created timestamp with time zone DEFAULT now() NOT NULL,
    date_updated timestamp with time zone,
    CONSTRAINT pk_result_review PRIMARY KEY (result_review_id),
    -- One decision per party per result; a rejected result is re-run, which makes a new result.
    CONSTRAINT ux_result_review_party UNIQUE (result_record_id, party),
    CONSTRAINT fk_result_review_result_tenant FOREIGN KEY (result_record_id, tenant_id)
        REFERENCES public.result_record (result_record_id, tenant_id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT ck_result_review_party_enum CHECK (party IN ('INTERNAL', 'CUSTOMER')),
    CONSTRAINT ck_result_review_decision_enum CHECK (decision IN ('APPROVED', 'REJECTED'))
);

COMMENT ON TABLE public.result_review IS 'A review of a result by one party, INTERNAL (our reviewer) or CUSTOMER: APPROVED or REJECTED, by whom, when, why. MIG-225.';

CREATE INDEX idx_result_review_tenant ON public.result_review (tenant_id);

CREATE TRIGGER result_review_tenant_id_from_parent BEFORE INSERT OR UPDATE OF result_record_id ON public.result_review
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('result_record', 'result_record_id', 'result_record_id');
