-- V195's way back: each table V195 dropped comes back EMPTY, as it stood on etl_job on 2026-09-30 (pg_dump -s): columns,
-- keys, indexes, comments, the read-only trigger of V61/V62/V63, row security and its policy, grants, and the sequences.
-- The rows were never here to restore -- V195 drops only an empty table. A table V195 left in place (it held rows) is
-- skipped, so this never fails on one.

DO $$
BEGIN
    IF to_regclass('public.ai_agent') IS NULL THEN
        CREATE SEQUENCE IF NOT EXISTS public.ai_agent_seq START WITH 1000 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
        GRANT SELECT, UPDATE, USAGE ON SEQUENCE public.ai_agent_seq TO process_app;
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
            updated_by bigint,
            CONSTRAINT ai_agent_pkey PRIMARY KEY (ai_agent_id),
            CONSTRAINT uk_ak9847os2u1gksfnotb3dvtcd UNIQUE (tool_uuid)
        );
        COMMENT ON TABLE public.ai_agent IS 'A configured AI assistant: provider, model, endpoint, encrypted key and its standing instructions. tool_uuid is an unguessable handle that exposes the agent as a callable tool.';
        CREATE INDEX idx_ai_agent_tenant_id ON public.ai_agent USING btree (tenant_id);
        CREATE TRIGGER ai_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.ai_agent
            FOR EACH STATEMENT EXECUTE FUNCTION public.ai_moved_read_only();
        ALTER TABLE public.ai_agent ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.ai_agent FORCE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.ai_agent TO process_app
            USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))))
            WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.ai_agent TO process_app;
    END IF;

    IF to_regclass('public.analytics_benchmark_result') IS NULL THEN
        CREATE SEQUENCE IF NOT EXISTS public.analytics_benchmark_result_seq START WITH 1000 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
        GRANT SELECT, UPDATE, USAGE ON SEQUENCE public.analytics_benchmark_result_seq TO process_app;
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
            updated_by bigint,
            storage_connection_id bigint,
            CONSTRAINT analytics_benchmark_result_pkey PRIMARY KEY (analytics_benchmark_result_id)
        );
        COMMENT ON TABLE public.analytics_benchmark_result IS 'One measured read of one dataset: what was measured, of what shape, how many times, and the spread -- not just a duration. Written only by the PLATFORM_ADMIN benchmark endpoint. Records format-versus-format and session cost; records nothing about reading in place versus loading into Postgres, which this application has no code path for.';
        COMMENT ON COLUMN public.analytics_benchmark_result.measure_kind IS 'FILE_OPEN (schema + count + first page, three governed sessions) or QUERY (one statement, one session). Which was chosen changes the number by more than the format does -- see sessions_per_run.';
        COMMENT ON COLUMN public.analytics_benchmark_result.sessions_per_run IS 'How many governed DuckDB sessions one measured run cost. A file open costs three, so that number carries the per-session cost three times; a query costs one. Recorded so the two are never silently compared.';
        COMMENT ON COLUMN public.analytics_benchmark_result.dataset_bytes IS 'Size on the object store, or null when it could not be established -- a glob names no single object. Null is "not known", never zero. Parquet''s advantage is largely compression, so a duration without this cannot say whether the format won or the file was simply smaller.';
        COMMENT ON COLUMN public.analytics_benchmark_result.run_durations_ms IS 'Every kept run in the order it ran, comma separated. The summary columns are recomputable from this; it is here so a reader can distrust the summary.';
        COMMENT ON COLUMN public.analytics_benchmark_result.limits_at_run IS 'The analytics limits in force when this was measured. A query result stops at the row ceiling, so two rows measured under different ceilings are not comparable, and the ceiling is not recoverable from anything else afterwards.';
        CREATE INDEX idx_analytics_benchmark_result_batch ON public.analytics_benchmark_result USING btree (batch_id);
        CREATE INDEX idx_analytics_benchmark_result_label ON public.analytics_benchmark_result USING btree (benchmark_label);
        CREATE INDEX idx_analytics_benchmark_result_tenant_date ON public.analytics_benchmark_result USING btree (tenant_id, date_created DESC);
        CREATE TRIGGER analytics_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.analytics_benchmark_result
            FOR EACH STATEMENT EXECUTE FUNCTION public.analytics_moved_read_only();
        ALTER TABLE public.analytics_benchmark_result ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.analytics_benchmark_result FORCE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.analytics_benchmark_result TO process_app
            USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))))
            WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.analytics_benchmark_result TO process_app;
    END IF;

    IF to_regclass('public.analytics_dataset') IS NULL THEN
        CREATE SEQUENCE IF NOT EXISTS public.analytics_dataset_seq START WITH 1000 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
        GRANT SELECT, UPDATE, USAGE ON SEQUENCE public.analytics_dataset_seq TO process_app;
        CREATE TABLE public.analytics_dataset (
            analytics_dataset_id bigint NOT NULL,
            tenant_id bigint,
            dataset_name character varying(255) NOT NULL,
            connection_alias character varying(255) NOT NULL,
            dataset_path text NOT NULL,
            dataset_format character varying(24) NOT NULL,
            date_created timestamp without time zone DEFAULT now() NOT NULL,
            created_by bigint,
            updated_by bigint,
            storage_connection_id bigint,
            CONSTRAINT analytics_dataset_pkey PRIMARY KEY (analytics_dataset_id)
        );
        COMMENT ON TABLE public.analytics_dataset IS 'A named dataset in Analytics Studio: a storage connection alias plus a path inside it. Saved queries, charts and benchmark results all point at one of these rather than naming a location themselves.';
        COMMENT ON COLUMN public.analytics_dataset.connection_alias IS 'The storage connection this dataset is read through. The bucket is NOT stored here -- it comes from the connection record at resolve time, so repointing a connection moves its saved datasets with it.';
        COMMENT ON COLUMN public.analytics_dataset.dataset_path IS 'The key inside the connection, or a glob when the dataset is a folder read as one table.';
        CREATE INDEX idx_analytics_dataset_tenant_id ON public.analytics_dataset USING btree (tenant_id);
        CREATE TRIGGER analytics_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.analytics_dataset
            FOR EACH STATEMENT EXECUTE FUNCTION public.analytics_moved_read_only();
        ALTER TABLE public.analytics_dataset ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.analytics_dataset FORCE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.analytics_dataset TO process_app
            USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))))
            WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.analytics_dataset TO process_app;
    END IF;

    IF to_regclass('public.payment') IS NULL THEN
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
            date_created timestamp without time zone DEFAULT now() NOT NULL,
            CONSTRAINT payment_pkey PRIMARY KEY (payment_id),
            CONSTRAINT payment_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoice(invoice_id)
        );
        COMMENT ON COLUMN public.payment.method IS 'bank | card | cash | credit_note | manual';
        COMMENT ON COLUMN public.payment.status IS 'submitted | verified | rejected';
        CREATE SEQUENCE IF NOT EXISTS public.payment_payment_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
        ALTER SEQUENCE public.payment_payment_id_seq OWNED BY public.payment.payment_id;
        ALTER TABLE ONLY public.payment ALTER COLUMN payment_id SET DEFAULT nextval('public.payment_payment_id_seq'::regclass);
        GRANT ALL ON SEQUENCE public.payment_payment_id_seq TO process_app;
        CREATE UNIQUE INDEX ux_payment_receipt_number ON public.payment USING btree (receipt_number) WHERE (receipt_number IS NOT NULL);
        CREATE TRIGGER billing_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.payment
            FOR EACH STATEMENT EXECUTE FUNCTION public.billing_moved_read_only();
        ALTER TABLE public.payment ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.payment FORCE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.payment TO process_app
            USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))))
            WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.payment TO process_app;
    END IF;

    IF to_regclass('public.timestamptz_v100_unrepresentable') IS NULL THEN
        CREATE TABLE public.timestamptz_v100_unrepresentable (
            table_name text NOT NULL,
            column_name text NOT NULL,
            row_key text NOT NULL,
            original timestamp without time zone NOT NULL,
            CONSTRAINT timestamptz_v100_unrepresentable_pkey PRIMARY KEY (table_name, column_name, row_key)
        );
        COMMENT ON TABLE public.timestamptz_v100_unrepresentable IS 'V100: stored values that were not a real America/Chicago time (the spring-forward gap), kept so that V100''s rollback restores them exactly. Dropped by that rollback.';
        GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.timestamptz_v100_unrepresentable TO process_app;
    END IF;
END
$$;
