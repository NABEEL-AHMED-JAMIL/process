-- The whole of the public schema, written down, so that an empty database can become this one.
--
-- Until now it could not. Liquibase created 21 tables; the other 16 -- tenant, app_user, the
-- entire job spine, both Kafka routing tables, the pipeline form builder, storage_connection,
-- notification, ai_agent and document_converter_task -- existed only because Hibernate made
-- them on a dev machine with ddl-auto=update. Twenty-three of the thirty-four sequences were in
-- the same position, app_user_seq and tenant_seq among them.
--
-- Stage and prod run ddl-auto=validate, which creates nothing. So a fresh database there never
-- got those tables, and the changelog could not supply them either: V12 adds foreign keys to
-- tenant and app_user, so a run against an empty database died at V12 and every changeset after
-- it. The schema that actually shipped was whatever Hibernate happened to produce, in whatever
-- order fields were added to the entity over two years, recorded nowhere.
--
-- That order is not cosmetic. job_queue's columns run alphabetically from date_created to
-- status -- Hibernate's default for the fields it knew about first -- and then bucket,
-- output_folder, attempt, next_attempt_at and the three callback_token columns, in the order
-- somebody added them. A dashboard drill-down reads that table with select * and takes its
-- columns by position. Build the table fresh from the entity today and Hibernate sorts all
-- nineteen fields alphabetically instead, so the positions move and the drill-down returns the
-- wrong values -- with no error, and no failing test.
--
-- So this file is a snapshot of the dev database's public schema, which is the only complete
-- and working instance of it, and from here it is the definition rather than the consequence.
-- V1 through V49 are kept under changelog-sets/archive for the reasons they record, but they
-- are no longer executed: they describe how this schema was arrived at, not what it is.
--
-- It is captured exactly as it stands, naive timestamps included. Declaring timestamptz here
-- would have made a fresh database differ from the one every developer already has, which is
-- the precise failure this file exists to end. The conversion is V51, and it moves every
-- environment together.
--
-- The precondition on the changeset means this runs on an empty database and is marked as
-- already run on one that has tenant -- so an existing database is untouched, and a new one
-- arrives at the same place.
--
-- The meter schema is deliberately absent. It belongs to etl_meter, the Python service, which
-- creates it. Two services defining one schema is a race worth not entering.


CREATE TABLE public.ai_agent (
    ai_agent_id bigint NOT NULL,
    agent_name character varying(255) NOT NULL,
    api_endpoint character varying(255),
    api_key character varying(1000),
    date_created timestamp without time zone,
    description text,
    instructions text NOT NULL,
    model character varying(255) NOT NULL,
    provider character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    target_file_types character varying(255) NOT NULL,
    json_mode boolean,
    tool_uuid character varying(36),
    tenant_id bigint,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.ai_agent IS 'A configured AI assistant: provider, model, endpoint, encrypted key and its standing instructions. tool_uuid is an unguessable handle that exposes the agent as a callable tool.';

CREATE SEQUENCE public.ai_agent_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE public.ai_model_connection_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.ai_model_connection (
    connection_id bigint DEFAULT nextval('public.ai_model_connection_seq'::regclass) NOT NULL,
    tenant_id bigint,
    name character varying(255) NOT NULL,
    provider character varying(64) NOT NULL,
    api_endpoint character varying(500),
    api_key character varying(1000),
    default_model character varying(255) NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    max_concurrency integer DEFAULT 4 NOT NULL,
    daily_token_budget bigint,
    status character varying(32) DEFAULT 'Active'::character varying NOT NULL,
    last_tested_at timestamp without time zone,
    last_test_ok boolean,
    last_test_message text,
    models_listed text,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.ai_model_connection IS 'Where a prompt runs: provider, endpoint, encrypted key, default model, caps. One default per workspace.';

CREATE SEQUENCE public.ai_prompt_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.ai_prompt (
    prompt_id bigint DEFAULT nextval('public.ai_prompt_seq'::regclass) NOT NULL,
    prompt_uuid character varying(36) NOT NULL,
    tenant_id bigint,
    name character varying(255) NOT NULL,
    description text,
    connection_id bigint,
    model character varying(255),
    system_instructions text,
    user_template text NOT NULL,
    variables text DEFAULT '[]'::text NOT NULL,
    output_mode character varying(16) DEFAULT 'text'::character varying NOT NULL,
    output_schema text,
    temperature numeric(3,2),
    max_tokens integer,
    tags character varying(500),
    version integer DEFAULT 1 NOT NULL,
    status character varying(32) DEFAULT 'Inactive'::character varying NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.ai_prompt IS 'What a step says to a model: system instructions, a message template with {{variables}}, the expected output. Versioned; a pipeline pins the version it was saved with.';

CREATE SEQUENCE public.ai_prompt_run_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.ai_prompt_run (
    run_id bigint DEFAULT nextval('public.ai_prompt_run_seq'::regclass) NOT NULL,
    tenant_id bigint,
    prompt_id bigint,
    prompt_version integer,
    connection_id bigint,
    kind character varying(16) NOT NULL,
    job_queue_id bigint,
    step_tag character varying(255),
    rendered_input text,
    output text,
    tokens_in integer,
    tokens_out integer,
    latency_ms integer,
    attempts integer DEFAULT 1 NOT NULL,
    status character varying(16) NOT NULL,
    error text,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint
);

CREATE TABLE public.ai_prompt_version (
    prompt_id bigint NOT NULL,
    version integer NOT NULL,
    connection_id bigint,
    model character varying(255),
    system_instructions text,
    user_template text NOT NULL,
    variables text DEFAULT '[]'::text NOT NULL,
    output_mode character varying(16) DEFAULT 'text'::character varying NOT NULL,
    output_schema text,
    temperature numeric(3,2),
    max_tokens integer,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint
);

CREATE TABLE public.analytics_analysis (
    analytics_analysis_id bigint NOT NULL,
    tenant_id bigint,
    analysis_name character varying(255) NOT NULL,
    connection_alias character varying(255) NOT NULL,
    dataset_path text NOT NULL,
    visualization_type character varying(32),
    analysis_config text NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    date_updated timestamp without time zone,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.analytics_analysis IS 'A saved Analytics Canvas analysis: a storage connection alias, a path inside it, and the dimension/measure/filter configuration as JSON. The bucket is NOT stored -- it comes from the connection record at resolve time, so repointing a connection moves its saved analyses with it.';

COMMENT ON COLUMN public.analytics_analysis.analysis_config IS 'Dimensions, measures, aggregation, filters, sort and top-N as JSON. One column rather than twenty because the shape is still moving; the cost is that Postgres cannot answer "which analyses group by department".';

CREATE SEQUENCE public.analytics_analysis_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.analytics_benchmark_result (
    analytics_benchmark_result_id bigint NOT NULL,
    tenant_id bigint,
    batch_id character varying(64) NOT NULL,
    benchmark_label character varying(255) NOT NULL,
    measure_kind character varying(24) NOT NULL,
    measured_what text NOT NULL,
    sessions_per_run integer NOT NULL,
    connection_alias character varying(255) NOT NULL,
    dataset_path text NOT NULL,
    dataset_format character varying(24) NOT NULL,
    query_text text,
    row_count bigint,
    column_count integer,
    dataset_bytes bigint,
    warmup_runs integer NOT NULL,
    measured_runs integer NOT NULL,
    min_ms bigint NOT NULL,
    median_ms bigint NOT NULL,
    max_ms bigint NOT NULL,
    mean_ms bigint NOT NULL,
    run_durations_ms text NOT NULL,
    limits_at_run character varying(255) NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.analytics_benchmark_result IS 'One measured read of one dataset: what was measured, of what shape, how many times, and the spread -- not just a duration. Written only by the PLATFORM_ADMIN benchmark endpoint. Records format-versus-format and session cost; records nothing about reading in place versus loading into Postgres, which this application has no code path for.';

COMMENT ON COLUMN public.analytics_benchmark_result.measure_kind IS 'FILE_OPEN (schema + count + first page, three governed sessions) or QUERY (one statement, one session). Which was chosen changes the number by more than the format does -- see sessions_per_run.';

COMMENT ON COLUMN public.analytics_benchmark_result.sessions_per_run IS 'How many governed DuckDB sessions one measured run cost. A file open costs three, so that number carries the per-session cost three times; a query costs one. Recorded so the two are never silently compared.';

COMMENT ON COLUMN public.analytics_benchmark_result.dataset_bytes IS 'Size on the object store, or null when it could not be established -- a glob names no single object. Null is "not known", never zero. Parquet''s advantage is largely compression, so a duration without this cannot say whether the format won or the file was simply smaller.';

COMMENT ON COLUMN public.analytics_benchmark_result.run_durations_ms IS 'Every kept run in the order it ran, comma separated. The summary columns are recomputable from this; it is here so a reader can distrust the summary.';

COMMENT ON COLUMN public.analytics_benchmark_result.limits_at_run IS 'The analytics limits in force when this was measured. A query result stops at the row ceiling, so two rows measured under different ceilings are not comparable, and the ceiling is not recoverable from anything else afterwards.';

CREATE SEQUENCE public.analytics_benchmark_result_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.analytics_dashboard (
    analytics_dashboard_id bigint NOT NULL,
    tenant_id bigint,
    dashboard_name character varying(255) NOT NULL,
    dashboard_description text,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    date_updated timestamp without time zone,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.analytics_dashboard IS 'Dashboard metadata: a name, a description and its owner. Holds no chart definition -- a widget row does that -- and does not settle whether Analytics Studio charts belong here or in the existing /reports pivot.';

CREATE SEQUENCE public.analytics_dashboard_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.analytics_dashboard_widget (
    analytics_dashboard_widget_id bigint NOT NULL,
    tenant_id bigint,
    analytics_dashboard_id bigint NOT NULL,
    widget_title character varying(255) NOT NULL,
    analytics_analysis_id bigint,
    analytics_query_id bigint,
    visualization_type character varying(32),
    widget_config text,
    display_order integer DEFAULT 0 NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    date_updated timestamp without time zone,
    created_by bigint,
    updated_by bigint,
    CONSTRAINT ck_analytics_dashboard_widget_one_source CHECK ((((analytics_analysis_id IS NOT NULL) AND (analytics_query_id IS NULL)) OR ((analytics_analysis_id IS NULL) AND (analytics_query_id IS NOT NULL))))
);

COMMENT ON TABLE public.analytics_dashboard_widget IS 'One tile on a dashboard, pointing at exactly one saved analysis or one saved query. Both foreign keys name (id, tenant_id) so the database refuses a widget that references another workspace''s row.';

COMMENT ON COLUMN public.analytics_dashboard_widget.display_order IS 'Position in the dashboard, coarsely. A finer layout belongs in widget_config until something server-side needs to read it.';

CREATE SEQUENCE public.analytics_dashboard_widget_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.analytics_dataset (
    analytics_dataset_id bigint NOT NULL,
    tenant_id bigint,
    dataset_name character varying(255) NOT NULL,
    connection_alias character varying(255) NOT NULL,
    dataset_path text NOT NULL,
    dataset_format character varying(24) NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.analytics_dataset IS 'A named dataset in Analytics Studio: a storage connection alias plus a path inside it. Saved queries, charts and benchmark results all point at one of these rather than naming a location themselves.';

COMMENT ON COLUMN public.analytics_dataset.connection_alias IS 'The storage connection this dataset is read through. The bucket is NOT stored here -- it comes from the connection record at resolve time, so repointing a connection moves its saved datasets with it.';

COMMENT ON COLUMN public.analytics_dataset.dataset_path IS 'The key inside the connection, or a glob when the dataset is a folder read as one table.';

CREATE SEQUENCE public.analytics_dataset_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.analytics_query (
    analytics_query_id bigint NOT NULL,
    tenant_id bigint,
    query_name character varying(255) NOT NULL,
    connection_alias character varying(255) NOT NULL,
    dataset_path text NOT NULL,
    query_text text NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    date_updated timestamp without time zone,
    created_by bigint,
    updated_by bigint,
    second_connection_alias character varying(255),
    second_dataset_path text,
    CONSTRAINT ck_analytics_query_second_dataset_pair CHECK ((((second_connection_alias IS NULL) AND (second_dataset_path IS NULL)) OR ((second_connection_alias IS NOT NULL) AND (second_dataset_path IS NOT NULL))))
);

COMMENT ON TABLE public.analytics_query IS 'A named, saved analytics query: a storage connection alias, a path inside it, and the SQL. The bucket is NOT stored -- it comes from the connection record at resolve time, so repointing a connection moves its saved queries with it.';

COMMENT ON COLUMN public.analytics_query.second_connection_alias IS 'Connection alias of the second dataset, registered as the view dataset2. Null when the query reads one file. Set only together with second_dataset_path.';

COMMENT ON COLUMN public.analytics_query.second_dataset_path IS 'Object key or glob of the second dataset, registered as the view dataset2. Null when the query reads one file. Set only together with second_connection_alias.';

CREATE TABLE public.analytics_query_run (
    analytics_query_run_id bigint NOT NULL,
    tenant_id bigint,
    analytics_query_id bigint,
    connection_alias character varying(255) NOT NULL,
    dataset_path text NOT NULL,
    query_text text NOT NULL,
    run_status character varying(24) NOT NULL,
    row_count bigint,
    duration_ms bigint,
    error_message text,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint,
    second_connection_alias character varying(255),
    second_dataset_path text
);

COMMENT ON TABLE public.analytics_query_run IS 'One row per analytics query executed: what ran, against which connection alias and path, by whom, when, for how long, how many rows and whether it failed. The module''s "who read what and when" record. Holds no credential and no resolved bucket URL, and is never pruned -- see the changeset for why.';

COMMENT ON COLUMN public.analytics_query_run.analytics_query_id IS 'The saved query this run came from, or null for an ad-hoc query. Set null when that saved query is deleted: the bookmark goes, the record that the data was read stays.';

COMMENT ON COLUMN public.analytics_query_run.error_message IS 'The user-facing sentence explain() produced, never the raw engine string -- engine errors quote the statement back, and the statement carries the resolved object-store location.';

COMMENT ON COLUMN public.analytics_query_run.second_connection_alias IS 'Connection alias of the second dataset this run read as dataset2, or null when it read one file.';

COMMENT ON COLUMN public.analytics_query_run.second_dataset_path IS 'Object key or glob of the second dataset this run read as dataset2, or null when it read one file.';

CREATE SEQUENCE public.analytics_query_run_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE public.analytics_query_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.app_user (
    app_user_id bigint NOT NULL,
    date_created timestamp without time zone,
    full_name character varying(255) NOT NULL,
    last_login_at timestamp without time zone,
    password character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    tenant_id bigint,
    user_role character varying(255) NOT NULL,
    username character varying(255) NOT NULL,
    uuid character varying(36),
    avatar_bucket character varying(255),
    avatar_key character varying(512),
    "position" character varying(120),
    must_change_password boolean DEFAULT false NOT NULL,
    created_by bigint,
    updated_by bigint,
    phone_number character varying(20),
    page_access_profile_id bigint
);

COMMENT ON TABLE public.app_user IS 'A person who can sign in, with their role (PLATFORM_ADMIN, TENANT_ADMIN, TENANT_USER) and the tenant they belong to. Soft-deleted: status Delete rather than a removed row.';

COMMENT ON COLUMN public.app_user.avatar_bucket IS 'Bucket holding the user''s picture; null when they have none.';

COMMENT ON COLUMN public.app_user.avatar_key IS 'Object key of the user''s picture: <appUserId>/profile/avatar.<ext>.';

COMMENT ON COLUMN public.app_user."position" IS 'Job title, e.g. Software Engineer or IT Administrator. Distinct from user_role, which is the permission level.';

COMMENT ON COLUMN public.app_user.must_change_password IS 'Set when an account is created with a generated password. Cleared once the person chooses their own.';

COMMENT ON COLUMN public.app_user.phone_number IS 'Phone in E.164: +<country code><national number>, digits only, 20 chars is the format maximum.';

CREATE SEQUENCE public.app_user_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.billing_account (
    billing_account_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    legal_name character varying(200),
    address character varying(600),
    billing_email character varying(200),
    tax_id character varying(64),
    tax_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    tax_label character varying(24),
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    payment_terms_days integer DEFAULT 30 NOT NULL,
    status character varying(16) DEFAULT 'Active'::character varying NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    date_updated timestamp without time zone,
    created_by bigint,
    updated_by bigint
);

COMMENT ON COLUMN public.billing_account.tax_id IS 'VAT/GST number; tax is applied only when this and a rate are set';

CREATE SEQUENCE public.billing_account_billing_account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.billing_account_billing_account_id_seq OWNED BY public.billing_account.billing_account_id;

CREATE TABLE public.billing_document (
    billing_document_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    invoice_id bigint,
    payment_id bigint,
    kind character varying(24) NOT NULL,
    number character varying(32),
    file_name character varying(200) NOT NULL,
    content_type character varying(100),
    size_bytes bigint,
    object_key character varying(512) NOT NULL,
    amount numeric(18,5),
    issued_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint
);

COMMENT ON COLUMN public.billing_document.kind IS 'invoice | credit_note | receipt | statement | payment_slip';

CREATE SEQUENCE public.billing_document_billing_document_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.billing_document_billing_document_id_seq OWNED BY public.billing_document.billing_document_id;

CREATE TABLE public.document_converter_task (
    document_converter_task_id bigint NOT NULL,
    bucket_name character varying(255) NOT NULL,
    date_created timestamp without time zone NOT NULL,
    input_content_type character varying(255),
    input_file_name character varying(255) NOT NULL,
    input_file_size bigint,
    input_format character varying(255) NOT NULL,
    input_storage_key character varying(255) NOT NULL,
    output_content_type character varying(255),
    output_file_name character varying(255),
    output_file_size bigint,
    output_format character varying(255) NOT NULL,
    output_storage_key character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    task_name character varying(255) NOT NULL,
    tenant_id bigint,
    target_folder character varying(255)
);

COMMENT ON TABLE public.document_converter_task IS 'A completed file conversion: the input and output formats, sizes, and the storage keys of both, so a converted file stays retrievable after the tab that made it is closed.';

CREATE SEQUENCE public.document_converter_task_id_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.invoice (
    invoice_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    number character varying(32) NOT NULL,
    kind character varying(16) DEFAULT 'invoice'::character varying NOT NULL,
    references_invoice_id bigint,
    period_start date NOT NULL,
    period_end date NOT NULL,
    status character varying(16) DEFAULT 'draft'::character varying NOT NULL,
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    subtotal numeric(18,5) DEFAULT 0 NOT NULL,
    tax_rate_percent numeric(6,3) DEFAULT 0 NOT NULL,
    tax numeric(18,5) DEFAULT 0 NOT NULL,
    total numeric(18,5) DEFAULT 0 NOT NULL,
    balance numeric(18,5) DEFAULT 0 NOT NULL,
    note character varying(600),
    issued_at timestamp without time zone,
    due_at timestamp without time zone,
    paid_at timestamp without time zone,
    voided_at timestamp without time zone,
    pdf_object_key character varying(512),
    rate_card_version integer,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    date_updated timestamp without time zone,
    created_by bigint,
    updated_by bigint,
    rate_card_name character varying(120)
);

COMMENT ON COLUMN public.invoice.kind IS 'invoice | credit_note';

COMMENT ON COLUMN public.invoice.status IS 'draft | issued | partially_paid | paid | overdue | void';

CREATE SEQUENCE public.invoice_invoice_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.invoice_invoice_id_seq OWNED BY public.invoice.invoice_id;

CREATE TABLE public.invoice_line (
    invoice_line_id bigint NOT NULL,
    invoice_id bigint NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    meter character varying(64),
    description character varying(300) NOT NULL,
    quantity numeric(18,6) DEFAULT 0 NOT NULL,
    unit character varying(24),
    per integer DEFAULT 1 NOT NULL,
    unit_price numeric(18,8) DEFAULT 0 NOT NULL,
    amount numeric(18,5) DEFAULT 0 NOT NULL,
    period_label character varying(32),
    manual boolean DEFAULT false NOT NULL,
    included_quantity numeric(20,5),
    billable_quantity numeric(20,5),
    pricing_detail text
);

CREATE SEQUENCE public.invoice_line_invoice_line_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.invoice_line_invoice_line_id_seq OWNED BY public.invoice_line.invoice_line_id;

CREATE TABLE public.job_audit_logs (
    job_audit_log_id bigint NOT NULL,
    date_created timestamp without time zone NOT NULL,
    job_queue_id bigint NOT NULL,
    log_detail text NOT NULL,
    status character varying(255) NOT NULL,
    external_id character varying(255)
);

COMMENT ON TABLE public.job_audit_logs IS 'Log lines a worker reported for one run, keyed by job_queue_id. Mirrored into OpenSearch; the API merges both sources and de-duplicates.';

CREATE SEQUENCE public.job_audit_logs_source_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.job_queue (
    job_queue_id bigint NOT NULL,
    date_created timestamp without time zone NOT NULL,
    end_time timestamp without time zone,
    job_id bigint NOT NULL,
    job_send boolean,
    job_status character varying(255) NOT NULL,
    job_status_message text,
    run_manual boolean,
    skip_manual boolean,
    skip_time timestamp without time zone,
    start_time timestamp without time zone,
    status character varying(255) NOT NULL,
    bucket character varying(255),
    output_folder character varying(255),
    attempt integer DEFAULT 1 NOT NULL,
    next_attempt_at timestamp without time zone,
    callback_token_hash character varying(64),
    callback_token_attempt integer,
    callback_token_expires_at timestamp without time zone
);

COMMENT ON TABLE public.job_queue IS 'One execution of a job -- queued, started, ended, final status and message, plus the flags recording whether it was started by hand and whether it reached the queue. Kept after its job is deleted, which is why run history survives.';

COMMENT ON COLUMN public.job_queue.attempt IS 'Which attempt this run is, starting at 1. Greater than 1 means an earlier attempt of this same slot failed and was retried; the reasons are in job_audit_logs.';

COMMENT ON COLUMN public.job_queue.next_attempt_at IS 'When a run awaiting retry becomes eligible for dispatch, in the application timezone. NULL for any run not waiting on a backoff, which is the normal case.';

COMMENT ON COLUMN public.job_queue.callback_token_hash IS 'SHA-256 (hex) of the token issued to the worker for this run''s callbacks; null once the run ends or if it was never dispatched.';

COMMENT ON COLUMN public.job_queue.callback_token_attempt IS 'The attempt the current callback token was minted for.';

COMMENT ON COLUMN public.job_queue.callback_token_expires_at IS 'When the current callback token stops being accepted, whatever the run''s state.';

CREATE SEQUENCE public.job_queue_source_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.kafka_connection_profile (
    kafka_connection_profile_id bigint NOT NULL,
    bootstrap_servers text NOT NULL,
    is_default boolean NOT NULL,
    date_created timestamp without time zone,
    profile_name character varying(255) NOT NULL,
    sasl_mechanism character varying(255),
    sasl_password character varying(1000),
    sasl_username character varying(255),
    security_protocol character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    additional_properties text,
    environment_label character varying(255),
    last_test_message text,
    last_tested_at timestamp without time zone,
    ssl_endpoint_identification_algorithm character varying(255),
    ssl_key_password_enc character varying(1000),
    ssl_keystore_location character varying(255),
    ssl_keystore_password_enc character varying(1000),
    ssl_truststore_location character varying(255),
    ssl_truststore_password_enc character varying(1000),
    tenant_id bigint,
    connection_status character varying(255),
    ssl_keystore_bucket character varying(255),
    ssl_truststore_bucket character varying(255),
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.kafka_connection_profile IS 'How to reach a Kafka cluster: brokers, security protocol, SASL details and the bucket paths of any TLS keystore and truststore. Secrets are stored encrypted and never returned to the UI.';

CREATE SEQUENCE public.kafka_connection_profile_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.lookup_data (
    lookup_id bigint NOT NULL,
    lookup_value text,
    lookup_type character varying(255),
    description character varying(255),
    date_created timestamp without time zone NOT NULL,
    parent_lookup_id bigint,
    is_encrypted boolean DEFAULT false NOT NULL,
    tenant_id bigint,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.lookup_data IS 'Shared key/value reference data used across screens. Self-referencing: a row with a parent is a sub-lookup of it.';

CREATE SEQUENCE public.lookup_id_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.notification (
    notification_id bigint NOT NULL,
    date_created timestamp without time zone NOT NULL,
    link_url character varying(255),
    message character varying(2000),
    is_read boolean NOT NULL,
    read_at timestamp without time zone,
    recipient_user_id bigint NOT NULL,
    severity character varying(255) NOT NULL,
    tenant_id bigint,
    title character varying(255) NOT NULL,
    type character varying(255) NOT NULL
);

COMMENT ON TABLE public.notification IS 'An in-app message for one recipient -- title, body, severity, read state and a link to the screen it refers to.';

CREATE SEQUENCE public.notification_notification_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.notification_notification_id_seq OWNED BY public.notification.notification_id;

CREATE TABLE public.page_access_profile (
    page_access_profile_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    profile_name character varying(100) NOT NULL,
    description character varying(500),
    is_default boolean DEFAULT false NOT NULL,
    status character varying(20) DEFAULT 'Active'::character varying NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    date_updated timestamp without time zone,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.page_access_profile IS 'A named bundle of console pages a workspace grants to its tenant users. One per person via app_user.page_access_profile_id; is_default names the bundle for people with none.';

CREATE TABLE public.page_access_profile_page (
    page_access_profile_id bigint NOT NULL,
    page_key character varying(64) NOT NULL
);

COMMENT ON TABLE public.page_access_profile_page IS 'The page keys (PageKey enum) inside a page_access_profile.';

CREATE SEQUENCE public.page_access_profile_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.payment (
    payment_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    invoice_id bigint NOT NULL,
    amount numeric(18,5) NOT NULL,
    method character varying(24) NOT NULL,
    reference character varying(120),
    note character varying(600),
    status character varying(16) DEFAULT 'submitted'::character varying NOT NULL,
    slip_object_key character varying(512),
    receipt_number character varying(32),
    submitted_by bigint,
    verified_by bigint,
    verified_at timestamp without time zone,
    received_at timestamp without time zone,
    date_created timestamp without time zone DEFAULT now() NOT NULL
);

COMMENT ON COLUMN public.payment.method IS 'bank | card | cash | credit_note | manual';

COMMENT ON COLUMN public.payment.status IS 'submitted | verified | rejected';

CREATE SEQUENCE public.payment_payment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.payment_payment_id_seq OWNED BY public.payment.payment_id;

CREATE TABLE public.pipeline (
    pipeline_key bigint NOT NULL,
    pipeline_id character varying(120) NOT NULL,
    pipeline_name character varying(255) NOT NULL,
    description text,
    tenant_id bigint,
    status character varying(24) DEFAULT 'Active'::character varying NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_by bigint,
    source_task_type_id bigint
);

COMMENT ON TABLE public.pipeline IS 'A pipeline a task can run: its public id (what the worker routes on), the topic it publishes on, and the fields a task on it fills in.';

COMMENT ON COLUMN public.pipeline.pipeline_key IS 'Surrogate key. pipeline_id is the public id the worker routes on.';

COMMENT ON COLUMN public.pipeline.source_task_type_id IS 'The topic this pipeline publishes on; null only for a pipeline defined before topics were required.';

CREATE TABLE public.pipeline_field (
    pipeline_field_id bigint NOT NULL,
    pipeline_key bigint NOT NULL,
    tag_key character varying(190) NOT NULL,
    tag_parent character varying(190),
    label character varying(255) NOT NULL,
    field_type character varying(32) DEFAULT 'text'::character varying NOT NULL,
    required boolean DEFAULT false NOT NULL,
    default_value text,
    help_text text,
    field_options text,
    "position" integer DEFAULT 0 NOT NULL,
    prompt_id bigint,
    variable_map text,
    on_error character varying(16),
    run_in character varying(16)
);

COMMENT ON TABLE public.pipeline_field IS 'One field of a pipeline: the XML tag it fills, and how it is presented to whoever fills it in.';

COMMENT ON COLUMN public.pipeline_field.field_options IS 'Choices for a select; ignored by every other field type. One per line, and on each line the first "=" optionally separates the stored value from the displayed label (lines=JSON Lines). Everything after that first "=" is the label, so a label may contain "=", ":" or "," freely. A line with no "=" is the legacy form and is both value and label -- existing tasks match their saved tag against an option value, so that must not change. A value cannot contain "=" and there is no escape character.';

COMMENT ON COLUMN public.pipeline_field.prompt_id IS 'field_type = ai: the prompt this step runs';

COMMENT ON COLUMN public.pipeline_field.variable_map IS 'field_type = ai: JSON {promptVariable: sourceTagKey}';

COMMENT ON COLUMN public.pipeline_field.on_error IS 'field_type = ai: fail (the run) | continue (empty tag)';

COMMENT ON COLUMN public.pipeline_field.run_in IS 'field_type = ai: server (before dispatch) | worker (the consumer runs it via aiPrompt.json/run)';

CREATE SEQUENCE public.pipeline_source_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.scheduler (
    scheduler_id bigint NOT NULL,
    date_created timestamp without time zone,
    end_date date,
    frequency character varying(255) NOT NULL,
    job_id bigint NOT NULL,
    interval_value character varying(255),
    start_date date NOT NULL,
    start_time time without time zone NOT NULL,
    days_of_week character varying(30),
    day_of_month smallint,
    next_run_at timestamp without time zone,
    expired boolean DEFAULT false NOT NULL,
    date_updated timestamp without time zone
);

COMMENT ON TABLE public.scheduler IS 'The timetable for one Auto job: frequency, interval, start and end dates, and the computed next_run_at the dispatcher polls. One row per job, no tenant_id -- it inherits from source_job.';

CREATE SEQUENCE public.scheduler_source_seq
    START WITH 1001
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp(3) without time zone,
    locked_at timestamp(3) without time zone,
    locked_by character varying(255)
);

COMMENT ON TABLE public.shedlock IS 'Scheduler mutex. Stops two application instances from firing the same scheduled run; managed by ShedLock, not application code.';

CREATE TABLE public.source_job (
    job_id bigint NOT NULL,
    complete_job boolean,
    date_created timestamp without time zone NOT NULL,
    execution character varying(255) NOT NULL,
    fail_job boolean,
    job_name character varying(1000) NOT NULL,
    job_running_status character varying(255),
    job_status character varying(255) NOT NULL,
    last_job_run timestamp without time zone,
    priority integer NOT NULL,
    skip_job boolean,
    task_detail_id bigint,
    tenant_id bigint,
    assigned_user_id bigint,
    created_by bigint,
    updated_by bigint,
    max_attempts integer DEFAULT 1 NOT NULL,
    retry_backoff_seconds integer DEFAULT 60 NOT NULL,
    CONSTRAINT ck_source_job_max_attempts CHECK (((max_attempts >= 1) AND (max_attempts <= 10))),
    CONSTRAINT ck_source_job_retry_backoff_seconds CHECK (((retry_backoff_seconds >= 1) AND (retry_backoff_seconds <= 3600)))
);

COMMENT ON TABLE public.source_job IS 'A task bound to a timetable. Holds run/execution settings, priority and the three email-notification flags; Auto jobs have a matching scheduler row, Manual ones run on demand. Soft-deleted via job_status.';

COMMENT ON COLUMN public.source_job.max_attempts IS 'Total attempts a run of this job may make, including the first. 1 (the default) disables retry and is the behaviour every job had before this column existed.';

COMMENT ON COLUMN public.source_job.retry_backoff_seconds IS 'Base delay before retrying a failed run. The wait doubles with each attempt, so 60 gives 60s then 120s then 240s.';

CREATE SEQUENCE public.source_job_source_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.source_task (
    task_detail_id bigint NOT NULL,
    home_page_id character varying(255),
    pipeline_id character varying(255),
    task_name character varying(255) NOT NULL,
    task_payload text,
    task_status character varying(255) NOT NULL,
    source_task_type_id bigint,
    tenant_id bigint,
    group_id character varying(255),
    bucket character varying(255),
    input_folder character varying(255),
    output_folder character varying(255),
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.source_task IS 'One unit of ETL work: where it reads, where it writes, and the task type that consumes it. Jobs point at a task; deleting a task cascades its jobs to Delete.';

CREATE TABLE public.source_task_payload (
    task_payload_id bigint NOT NULL,
    tag_key character varying(255),
    tag_parent character varying(255),
    tag_value text,
    payload_id bigint
);

COMMENT ON TABLE public.source_task_payload IS 'The tag tree of a task''s XML payload, one row per tag. payload_id is a foreign key to source_task.task_detail_id despite the name, so it inherits its tenant from the task.';

CREATE SEQUENCE public.source_task_payload_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.source_task_type (
    source_task_type_id bigint NOT NULL,
    service_name character varying(255) NOT NULL,
    description character varying(255) NOT NULL,
    queue_topic_partition character varying(255) NOT NULL,
    task_type_status character varying(255),
    kafka_connection_profile_id bigint,
    tenant_id bigint NOT NULL,
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.source_task_type IS 'A consumer class -- the service that runs a task and the Kafka topic and partitions it listens on, stored as topic=name&partitions=[n].';

COMMENT ON COLUMN public.source_task_type.tenant_id IS 'The one workspace this task type belongs to. Not nullable: a NULL used to mean "shared with every workspace", which is how one workspace came to see another''s Kafka topics on the Task Types screen.';

CREATE SEQUENCE public.source_task_type_source_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.storage_connection (
    storage_connection_id bigint NOT NULL,
    access_key character varying(255),
    alias character varying(255) NOT NULL,
    azure_account_name character varying(255),
    azure_connection_string_enc character varying(2000),
    base_directory character varying(255),
    bucket_name character varying(255),
    connection_name character varying(255) NOT NULL,
    connection_status character varying(255),
    date_created timestamp without time zone,
    description character varying(255),
    endpoint character varying(255),
    host character varying(255),
    implicit_tls boolean,
    is_default boolean NOT NULL,
    last_test_message text,
    last_tested_at timestamp without time zone,
    passive_mode boolean,
    password_enc character varying(1000),
    port integer,
    provider character varying(255) NOT NULL,
    region character varying(255),
    secret_key_enc character varying(1000),
    status character varying(255) NOT NULL,
    tenant_id bigint,
    username character varying(255),
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.storage_connection IS 'Credentials and settings for one storage backend (S3, MinIO, Azure, FTP, FTPS). The object browser and every bucket path in the app resolve through these.';

CREATE SEQUENCE public.storage_connection_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE public.task_detail_source_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.tenant (
    tenant_id bigint NOT NULL,
    date_created timestamp without time zone,
    status character varying(255) NOT NULL,
    tenant_code character varying(255) NOT NULL,
    tenant_name character varying(255) NOT NULL,
    uuid character varying(36),
    created_by bigint,
    updated_by bigint
);

COMMENT ON TABLE public.tenant IS 'An isolated workspace. Every tenant-scoped table keys back to this one, and nothing crosses between tenants except for a platform admin.';

CREATE TABLE public.tenant_request (
    tenant_request_id bigint NOT NULL,
    organisation_name character varying(255) NOT NULL,
    contact_name character varying(255) NOT NULL,
    contact_email character varying(255) NOT NULL,
    purpose text,
    status character varying(24) DEFAULT 'Pending'::character varying NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    decided_at timestamp without time zone,
    decided_by bigint,
    decision_note text,
    created_tenant_id bigint,
    created_user_id bigint
);

COMMENT ON TABLE public.tenant_request IS 'A request from outside for a workspace. Becomes a tenant and its first administrator only when a platform administrator approves it.';

CREATE SEQUENCE public.tenant_request_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE public.tenant_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.tenant_task_type_kafka_route (
    tenant_task_type_kafka_route_id bigint NOT NULL,
    date_created timestamp without time zone,
    kafka_connection_profile_id bigint NOT NULL,
    source_task_type_id bigint NOT NULL,
    tenant_id bigint NOT NULL
);

COMMENT ON TABLE public.tenant_task_type_kafka_route IS 'Routes one tenant''s task type to a specific Kafka profile, so two tenants sharing a task type can publish to different clusters.';

CREATE SEQUENCE public.tenant_task_type_kafka_route_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.user_page_access (
    app_user_id bigint NOT NULL,
    page_key character varying(64) NOT NULL,
    allowed boolean NOT NULL,
    date_created timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint
);

COMMENT ON TABLE public.user_page_access IS 'Per-person exceptions to the access profile: one row per page that differs from the profile, allowed=true opens it, false withholds it.';

ALTER TABLE ONLY public.billing_account ALTER COLUMN billing_account_id SET DEFAULT nextval('public.billing_account_billing_account_id_seq'::regclass);

ALTER TABLE ONLY public.billing_document ALTER COLUMN billing_document_id SET DEFAULT nextval('public.billing_document_billing_document_id_seq'::regclass);

ALTER TABLE ONLY public.invoice ALTER COLUMN invoice_id SET DEFAULT nextval('public.invoice_invoice_id_seq'::regclass);

ALTER TABLE ONLY public.invoice_line ALTER COLUMN invoice_line_id SET DEFAULT nextval('public.invoice_line_invoice_line_id_seq'::regclass);

ALTER TABLE ONLY public.notification ALTER COLUMN notification_id SET DEFAULT nextval('public.notification_notification_id_seq'::regclass);

ALTER TABLE ONLY public.payment ALTER COLUMN payment_id SET DEFAULT nextval('public.payment_payment_id_seq'::regclass);

ALTER TABLE ONLY public.ai_agent
    ADD CONSTRAINT ai_agent_pkey PRIMARY KEY (ai_agent_id);

ALTER TABLE ONLY public.ai_model_connection
    ADD CONSTRAINT ai_model_connection_pkey PRIMARY KEY (connection_id);

ALTER TABLE ONLY public.ai_prompt
    ADD CONSTRAINT ai_prompt_pkey PRIMARY KEY (prompt_id);

ALTER TABLE ONLY public.ai_prompt
    ADD CONSTRAINT ai_prompt_prompt_uuid_key UNIQUE (prompt_uuid);

ALTER TABLE ONLY public.ai_prompt_run
    ADD CONSTRAINT ai_prompt_run_pkey PRIMARY KEY (run_id);

ALTER TABLE ONLY public.ai_prompt_version
    ADD CONSTRAINT ai_prompt_version_pkey PRIMARY KEY (prompt_id, version);

ALTER TABLE ONLY public.analytics_analysis
    ADD CONSTRAINT analytics_analysis_pkey PRIMARY KEY (analytics_analysis_id);

ALTER TABLE ONLY public.analytics_benchmark_result
    ADD CONSTRAINT analytics_benchmark_result_pkey PRIMARY KEY (analytics_benchmark_result_id);

ALTER TABLE ONLY public.analytics_dashboard
    ADD CONSTRAINT analytics_dashboard_pkey PRIMARY KEY (analytics_dashboard_id);

ALTER TABLE ONLY public.analytics_dashboard_widget
    ADD CONSTRAINT analytics_dashboard_widget_pkey PRIMARY KEY (analytics_dashboard_widget_id);

ALTER TABLE ONLY public.analytics_dataset
    ADD CONSTRAINT analytics_dataset_pkey PRIMARY KEY (analytics_dataset_id);

ALTER TABLE ONLY public.analytics_query
    ADD CONSTRAINT analytics_query_pkey PRIMARY KEY (analytics_query_id);

ALTER TABLE ONLY public.analytics_query_run
    ADD CONSTRAINT analytics_query_run_pkey PRIMARY KEY (analytics_query_run_id);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (app_user_id);

ALTER TABLE ONLY public.billing_account
    ADD CONSTRAINT billing_account_pkey PRIMARY KEY (billing_account_id);

ALTER TABLE ONLY public.billing_account
    ADD CONSTRAINT billing_account_tenant_id_key UNIQUE (tenant_id);

ALTER TABLE ONLY public.billing_document
    ADD CONSTRAINT billing_document_pkey PRIMARY KEY (billing_document_id);

ALTER TABLE ONLY public.document_converter_task
    ADD CONSTRAINT document_converter_task_pkey PRIMARY KEY (document_converter_task_id);

ALTER TABLE ONLY public.invoice_line
    ADD CONSTRAINT invoice_line_pkey PRIMARY KEY (invoice_line_id);

ALTER TABLE ONLY public.invoice
    ADD CONSTRAINT invoice_number_key UNIQUE (number);

ALTER TABLE ONLY public.invoice
    ADD CONSTRAINT invoice_pkey PRIMARY KEY (invoice_id);

ALTER TABLE ONLY public.job_audit_logs
    ADD CONSTRAINT job_audit_logs_pkey PRIMARY KEY (job_audit_log_id);

ALTER TABLE ONLY public.job_queue
    ADD CONSTRAINT job_queue_pkey PRIMARY KEY (job_queue_id);

ALTER TABLE ONLY public.kafka_connection_profile
    ADD CONSTRAINT kafka_connection_profile_pkey PRIMARY KEY (kafka_connection_profile_id);

ALTER TABLE ONLY public.lookup_data
    ADD CONSTRAINT lookup_data_lookup_type_key UNIQUE (lookup_type);

ALTER TABLE ONLY public.lookup_data
    ADD CONSTRAINT lookup_data_pkey PRIMARY KEY (lookup_id);

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_pkey PRIMARY KEY (notification_id);

ALTER TABLE ONLY public.page_access_profile
    ADD CONSTRAINT page_access_profile_pkey PRIMARY KEY (page_access_profile_id);

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT payment_pkey PRIMARY KEY (payment_id);

ALTER TABLE ONLY public.pipeline_field
    ADD CONSTRAINT pipeline_field_pkey PRIMARY KEY (pipeline_field_id);

ALTER TABLE ONLY public.pipeline
    ADD CONSTRAINT pipeline_pkey PRIMARY KEY (pipeline_key);

ALTER TABLE ONLY public.page_access_profile_page
    ADD CONSTRAINT pk_page_access_profile_page PRIMARY KEY (page_access_profile_id, page_key);

ALTER TABLE ONLY public.user_page_access
    ADD CONSTRAINT pk_user_page_access PRIMARY KEY (app_user_id, page_key);

ALTER TABLE ONLY public.scheduler
    ADD CONSTRAINT scheduler_pkey PRIMARY KEY (scheduler_id);

ALTER TABLE ONLY public.shedlock
    ADD CONSTRAINT shedlock_pkey PRIMARY KEY (name);

ALTER TABLE ONLY public.source_job
    ADD CONSTRAINT source_job_pkey PRIMARY KEY (job_id);

ALTER TABLE ONLY public.source_task_payload
    ADD CONSTRAINT source_task_payload_pkey PRIMARY KEY (task_payload_id);

ALTER TABLE ONLY public.source_task
    ADD CONSTRAINT source_task_pkey PRIMARY KEY (task_detail_id);

ALTER TABLE ONLY public.source_task_type
    ADD CONSTRAINT source_task_type_pkey PRIMARY KEY (source_task_type_id);

ALTER TABLE ONLY public.storage_connection
    ADD CONSTRAINT storage_connection_pkey PRIMARY KEY (storage_connection_id);

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (tenant_id);

ALTER TABLE ONLY public.tenant_request
    ADD CONSTRAINT tenant_request_pkey PRIMARY KEY (tenant_request_id);

ALTER TABLE ONLY public.tenant_task_type_kafka_route
    ADD CONSTRAINT tenant_task_type_kafka_route_pkey PRIMARY KEY (tenant_task_type_kafka_route_id);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT uk_3k4cplvh82srueuttfkwnylq0 UNIQUE (username);

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT uk_4dwx7sonk7tq4x03en9377rou UNIQUE (uuid);

ALTER TABLE ONLY public.ai_agent
    ADD CONSTRAINT uk_ak9847os2u1gksfnotb3dvtcd UNIQUE (tool_uuid);

ALTER TABLE ONLY public.analytics_analysis
    ADD CONSTRAINT uk_analytics_analysis_id_tenant UNIQUE (analytics_analysis_id, tenant_id);

ALTER TABLE ONLY public.analytics_dashboard
    ADD CONSTRAINT uk_analytics_dashboard_id_tenant UNIQUE (analytics_dashboard_id, tenant_id);

ALTER TABLE ONLY public.analytics_query
    ADD CONSTRAINT uk_analytics_query_id_tenant UNIQUE (analytics_query_id, tenant_id);

ALTER TABLE ONLY public.job_audit_logs
    ADD CONSTRAINT uk_dfix0i3vdlmj15gj9fiqae1ge UNIQUE (external_id);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT uk_fl8f83s53808r6xtilfaglkb UNIQUE (uuid);

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT uk_ng2jtiduv4m34nlcypgqdp29j UNIQUE (tenant_code);

ALTER TABLE ONLY public.scheduler
    ADD CONSTRAINT uk_scheduler_job_id UNIQUE (job_id);

COMMENT ON CONSTRAINT uk_scheduler_job_id ON public.scheduler IS 'A job has at most one timetable. findSchedulerByJobId returns a single row, so a second row turns every by-id read of that job into a 500.';

ALTER TABLE ONLY public.page_access_profile
    ADD CONSTRAINT uq_page_access_profile_name UNIQUE (tenant_id, profile_name);

ALTER TABLE ONLY public.storage_connection
    ADD CONSTRAINT uq_storage_connection_alias UNIQUE (alias);

ALTER TABLE ONLY public.tenant_task_type_kafka_route
    ADD CONSTRAINT uq_tenant_task_type UNIQUE (tenant_id, source_task_type_id);

CREATE INDEX billing_document_tenant ON public.billing_document USING btree (tenant_id, issued_at);

CREATE INDEX idx_ai_agent_tenant_id ON public.ai_agent USING btree (tenant_id);

CREATE INDEX idx_analytics_analysis_tenant_id ON public.analytics_analysis USING btree (tenant_id);

CREATE INDEX idx_analytics_benchmark_result_batch ON public.analytics_benchmark_result USING btree (batch_id);

CREATE INDEX idx_analytics_benchmark_result_label ON public.analytics_benchmark_result USING btree (benchmark_label);

CREATE INDEX idx_analytics_benchmark_result_tenant_date ON public.analytics_benchmark_result USING btree (tenant_id, date_created DESC);

CREATE INDEX idx_analytics_dashboard_tenant_id ON public.analytics_dashboard USING btree (tenant_id);

CREATE INDEX idx_analytics_dashboard_widget_analysis ON public.analytics_dashboard_widget USING btree (analytics_analysis_id, tenant_id);

CREATE INDEX idx_analytics_dashboard_widget_dashboard ON public.analytics_dashboard_widget USING btree (analytics_dashboard_id, tenant_id, display_order);

CREATE INDEX idx_analytics_dashboard_widget_query ON public.analytics_dashboard_widget USING btree (analytics_query_id, tenant_id);

CREATE INDEX idx_analytics_dataset_tenant_id ON public.analytics_dataset USING btree (tenant_id);

CREATE INDEX idx_analytics_query_run_query_id ON public.analytics_query_run USING btree (analytics_query_id);

CREATE INDEX idx_analytics_query_run_tenant_date ON public.analytics_query_run USING btree (tenant_id, date_created DESC);

CREATE INDEX idx_analytics_query_tenant_id ON public.analytics_query USING btree (tenant_id);

CREATE INDEX idx_app_user_page_access_profile_id ON public.app_user USING btree (page_access_profile_id);

CREATE INDEX idx_app_user_tenant_id ON public.app_user USING btree (tenant_id);

CREATE INDEX idx_document_converter_task_tenant_id ON public.document_converter_task USING btree (tenant_id);

CREATE INDEX idx_job_audit_logs_job_queue_id ON public.job_audit_logs USING btree (job_queue_id);

CREATE INDEX idx_job_queue_job_id ON public.job_queue USING btree (job_id);

CREATE INDEX idx_job_queue_next_attempt_at ON public.job_queue USING btree (next_attempt_at) WHERE (next_attempt_at IS NOT NULL);

CREATE INDEX idx_kcp_tenant_id ON public.kafka_connection_profile USING btree (tenant_id);

CREATE INDEX idx_lookup_data_parent ON public.lookup_data USING btree (parent_lookup_id);

CREATE INDEX idx_lookup_data_tenant_id ON public.lookup_data USING btree (tenant_id);

CREATE INDEX idx_notification_recipient ON public.notification USING btree (recipient_user_id);

CREATE INDEX idx_notification_recipient_read ON public.notification USING btree (recipient_user_id, is_read);

CREATE INDEX idx_notification_tenant_id ON public.notification USING btree (tenant_id);

CREATE INDEX idx_page_access_profile_tenant_id ON public.page_access_profile USING btree (tenant_id);

CREATE INDEX idx_scheduler_job_id ON public.scheduler USING btree (job_id);

CREATE INDEX idx_scheduler_next_run_at ON public.scheduler USING btree (next_run_at);

CREATE INDEX idx_source_job_assigned_user_id ON public.source_job USING btree (assigned_user_id);

CREATE INDEX idx_source_job_task_detail_id ON public.source_job USING btree (task_detail_id);

CREATE INDEX idx_source_job_tenant_id ON public.source_job USING btree (tenant_id);

CREATE INDEX idx_source_task_payload_task ON public.source_task_payload USING btree (payload_id);

CREATE INDEX idx_source_task_tenant_id ON public.source_task USING btree (tenant_id);

CREATE INDEX idx_source_task_type_id ON public.source_task USING btree (source_task_type_id);

CREATE INDEX idx_storage_connection_tenant_id ON public.storage_connection USING btree (tenant_id);

CREATE INDEX idx_stt_kafka_profile_id ON public.source_task_type USING btree (kafka_connection_profile_id);

CREATE INDEX idx_stt_tenant_id ON public.source_task_type USING btree (tenant_id);

CREATE INDEX idx_ttkr_profile_id ON public.tenant_task_type_kafka_route USING btree (kafka_connection_profile_id);

CREATE INDEX idx_ttkr_task_type_id ON public.tenant_task_type_kafka_route USING btree (source_task_type_id);

CREATE INDEX idx_ttkr_tenant_id ON public.tenant_task_type_kafka_route USING btree (tenant_id);

CREATE INDEX invoice_tenant_period ON public.invoice USING btree (tenant_id, period_start);

CREATE INDEX ix_ai_model_connection_tenant ON public.ai_model_connection USING btree (tenant_id);

CREATE INDEX ix_ai_prompt_connection ON public.ai_prompt USING btree (connection_id);

CREATE INDEX ix_ai_prompt_run_prompt ON public.ai_prompt_run USING btree (prompt_id, date_created DESC);

CREATE INDEX ix_ai_prompt_run_tenant_day ON public.ai_prompt_run USING btree (tenant_id, date_created);

CREATE INDEX ix_ai_prompt_tenant ON public.ai_prompt USING btree (tenant_id);

CREATE INDEX ix_pipeline_field_pipeline ON public.pipeline_field USING btree (pipeline_key);

CREATE INDEX ix_pipeline_field_prompt ON public.pipeline_field USING btree (prompt_id) WHERE (prompt_id IS NOT NULL);

CREATE INDEX ix_pipeline_tenant ON public.pipeline USING btree (tenant_id);

CREATE INDEX ix_pipeline_topic ON public.pipeline USING btree (source_task_type_id);

CREATE INDEX ix_tenant_request_status ON public.tenant_request USING btree (status);

CREATE UNIQUE INDEX uq_page_access_profile_default ON public.page_access_profile USING btree (tenant_id) WHERE is_default;

CREATE UNIQUE INDEX ux_ai_prompt_run_step ON public.ai_prompt_run USING btree (job_queue_id, step_tag) WHERE (job_queue_id IS NOT NULL);

CREATE UNIQUE INDEX ux_pipeline_id_tenant ON public.pipeline USING btree (pipeline_id, COALESCE(tenant_id, ('-1'::integer)::bigint)) WHERE ((status)::text <> 'Delete'::text);

CREATE UNIQUE INDEX ux_tenant_request_open_email ON public.tenant_request USING btree (lower((contact_email)::text)) WHERE ((status)::text = 'Pending'::text);

ALTER TABLE ONLY public.ai_model_connection
    ADD CONSTRAINT ai_model_connection_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.ai_prompt
    ADD CONSTRAINT ai_prompt_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.ai_model_connection(connection_id);

ALTER TABLE ONLY public.ai_prompt_run
    ADD CONSTRAINT ai_prompt_run_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES public.ai_prompt(prompt_id) ON DELETE SET NULL;

ALTER TABLE ONLY public.ai_prompt
    ADD CONSTRAINT ai_prompt_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.ai_prompt_version
    ADD CONSTRAINT ai_prompt_version_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES public.ai_prompt(prompt_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.storage_connection
    ADD CONSTRAINT fk1l154qmvj2jbw1qejitttx1ms FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.ai_agent
    ADD CONSTRAINT fk_ai_agent_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.analytics_analysis
    ADD CONSTRAINT fk_analytics_analysis_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_analysis
    ADD CONSTRAINT fk_analytics_analysis_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.analytics_analysis
    ADD CONSTRAINT fk_analytics_analysis_updated_by FOREIGN KEY (updated_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_benchmark_result
    ADD CONSTRAINT fk_analytics_benchmark_result_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_benchmark_result
    ADD CONSTRAINT fk_analytics_benchmark_result_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.analytics_benchmark_result
    ADD CONSTRAINT fk_analytics_benchmark_result_updated_by FOREIGN KEY (updated_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_dashboard
    ADD CONSTRAINT fk_analytics_dashboard_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_dashboard
    ADD CONSTRAINT fk_analytics_dashboard_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.analytics_dashboard
    ADD CONSTRAINT fk_analytics_dashboard_updated_by FOREIGN KEY (updated_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_dashboard_widget
    ADD CONSTRAINT fk_analytics_dashboard_widget_analysis FOREIGN KEY (analytics_analysis_id, tenant_id) REFERENCES public.analytics_analysis(analytics_analysis_id, tenant_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.analytics_dashboard_widget
    ADD CONSTRAINT fk_analytics_dashboard_widget_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_dashboard_widget
    ADD CONSTRAINT fk_analytics_dashboard_widget_dashboard FOREIGN KEY (analytics_dashboard_id, tenant_id) REFERENCES public.analytics_dashboard(analytics_dashboard_id, tenant_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.analytics_dashboard_widget
    ADD CONSTRAINT fk_analytics_dashboard_widget_query FOREIGN KEY (analytics_query_id, tenant_id) REFERENCES public.analytics_query(analytics_query_id, tenant_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.analytics_dashboard_widget
    ADD CONSTRAINT fk_analytics_dashboard_widget_updated_by FOREIGN KEY (updated_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_dataset
    ADD CONSTRAINT fk_analytics_dataset_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_dataset
    ADD CONSTRAINT fk_analytics_dataset_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.analytics_dataset
    ADD CONSTRAINT fk_analytics_dataset_updated_by FOREIGN KEY (updated_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_query
    ADD CONSTRAINT fk_analytics_query_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_query_run
    ADD CONSTRAINT fk_analytics_query_run_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.analytics_query_run
    ADD CONSTRAINT fk_analytics_query_run_query FOREIGN KEY (analytics_query_id) REFERENCES public.analytics_query(analytics_query_id) ON DELETE SET NULL;

ALTER TABLE ONLY public.analytics_query_run
    ADD CONSTRAINT fk_analytics_query_run_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.analytics_query
    ADD CONSTRAINT fk_analytics_query_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.analytics_query
    ADD CONSTRAINT fk_analytics_query_updated_by FOREIGN KEY (updated_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT fk_app_user_page_access_profile FOREIGN KEY (page_access_profile_id) REFERENCES public.page_access_profile(page_access_profile_id) ON DELETE SET NULL;

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.document_converter_task
    ADD CONSTRAINT fk_document_converter_task_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.job_audit_logs
    ADD CONSTRAINT fk_job_audit_logs_job_queue FOREIGN KEY (job_queue_id) REFERENCES public.job_queue(job_queue_id);

ALTER TABLE ONLY public.job_queue
    ADD CONSTRAINT fk_job_queue_source_job FOREIGN KEY (job_id) REFERENCES public.source_job(job_id);

ALTER TABLE ONLY public.kafka_connection_profile
    ADD CONSTRAINT fk_kafka_connection_profile_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.lookup_data
    ADD CONSTRAINT fk_lookup_data_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.page_access_profile
    ADD CONSTRAINT fk_page_access_profile_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.page_access_profile_page
    ADD CONSTRAINT fk_page_access_profile_page_profile FOREIGN KEY (page_access_profile_id) REFERENCES public.page_access_profile(page_access_profile_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.page_access_profile
    ADD CONSTRAINT fk_page_access_profile_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.page_access_profile
    ADD CONSTRAINT fk_page_access_profile_updated_by FOREIGN KEY (updated_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.scheduler
    ADD CONSTRAINT fk_scheduler_source_job FOREIGN KEY (job_id) REFERENCES public.source_job(job_id);

ALTER TABLE ONLY public.source_job
    ADD CONSTRAINT fk_source_job_assigned_user FOREIGN KEY (assigned_user_id) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.source_job
    ADD CONSTRAINT fk_source_job_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.source_task
    ADD CONSTRAINT fk_source_task_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.source_task_type
    ADD CONSTRAINT fk_source_task_type_kafka_profile FOREIGN KEY (kafka_connection_profile_id) REFERENCES public.kafka_connection_profile(kafka_connection_profile_id);

ALTER TABLE ONLY public.source_task_type
    ADD CONSTRAINT fk_source_task_type_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.tenant_task_type_kafka_route
    ADD CONSTRAINT fk_tenant_task_type_kafka_route_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);

ALTER TABLE ONLY public.tenant_task_type_kafka_route
    ADD CONSTRAINT fk_ttkr_kafka_profile FOREIGN KEY (kafka_connection_profile_id) REFERENCES public.kafka_connection_profile(kafka_connection_profile_id);

ALTER TABLE ONLY public.tenant_task_type_kafka_route
    ADD CONSTRAINT fk_ttkr_source_task_type FOREIGN KEY (source_task_type_id) REFERENCES public.source_task_type(source_task_type_id);

ALTER TABLE ONLY public.user_page_access
    ADD CONSTRAINT fk_user_page_access_created_by FOREIGN KEY (created_by) REFERENCES public.app_user(app_user_id);

ALTER TABLE ONLY public.user_page_access
    ADD CONSTRAINT fk_user_page_access_user FOREIGN KEY (app_user_id) REFERENCES public.app_user(app_user_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.source_job
    ADD CONSTRAINT fkoa8dvmexqhn9yx2wdvcw5uruo FOREIGN KEY (task_detail_id) REFERENCES public.source_task(task_detail_id);

ALTER TABLE ONLY public.source_task
    ADD CONSTRAINT fkraku9l86l67hy1equcn8k7ra7 FOREIGN KEY (source_task_type_id) REFERENCES public.source_task_type(source_task_type_id);

ALTER TABLE ONLY public.source_task_payload
    ADD CONSTRAINT fkswqqs563ub2iw8xio9lt4akel FOREIGN KEY (payload_id) REFERENCES public.source_task(task_detail_id);

ALTER TABLE ONLY public.invoice_line
    ADD CONSTRAINT invoice_line_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoice(invoice_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lookup_data
    ADD CONSTRAINT lookup_data_parent_lookup_id_fkey FOREIGN KEY (parent_lookup_id) REFERENCES public.lookup_data(lookup_id);

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT payment_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoice(invoice_id);

ALTER TABLE ONLY public.pipeline_field
    ADD CONSTRAINT pipeline_field_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES public.ai_prompt(prompt_id);

ALTER TABLE ONLY public.pipeline
    ADD CONSTRAINT pipeline_source_task_type_id_fkey FOREIGN KEY (source_task_type_id) REFERENCES public.source_task_type(source_task_type_id);

ALTER TABLE ONLY public.pipeline_field
    ADD CONSTRAINT task_form_field_task_form_id_fkey FOREIGN KEY (pipeline_key) REFERENCES public.pipeline(pipeline_key) ON DELETE CASCADE;

