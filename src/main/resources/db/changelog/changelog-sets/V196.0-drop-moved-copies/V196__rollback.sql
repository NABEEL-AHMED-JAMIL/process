-- V196's way back: each table V196 dropped comes back EMPTY, as it stood on etl_job on 2026-10-01 (pg_dump -s): columns,
-- keys, indexes, comments, the read-only trigger of V61/V62/V63, row security and its policy, grants, and the sequences.
-- The rows are not here: they are in etl-platform/backups/2026-10-01-etl_job-<table>.sql (pg_dump of each table, schema
-- and data, taken just before V196 ran on the live instance). Parents come back before their children. A table that is
-- already there is skipped, so this never fails on one.

DO $$
BEGIN
    IF to_regclass('public.ai_model_connection') IS NULL THEN
        CREATE SEQUENCE IF NOT EXISTS public.ai_model_connection_seq
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
        ALTER TABLE ONLY public.ai_model_connection FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.ai_model_connection IS 'Where a prompt runs: provider, endpoint, encrypted key, default model, caps. One default per workspace.';
        ALTER TABLE ONLY public.ai_model_connection
            ADD CONSTRAINT ai_model_connection_pkey PRIMARY KEY (connection_id);
        CREATE INDEX ix_ai_model_connection_tenant ON public.ai_model_connection USING btree (tenant_id);
        CREATE TRIGGER ai_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.ai_model_connection FOR EACH STATEMENT EXECUTE FUNCTION public.ai_moved_read_only();
        ALTER TABLE public.ai_model_connection ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.ai_model_connection TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT ALL ON SEQUENCE public.ai_model_connection_seq TO process_app;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.ai_model_connection TO process_app;
    END IF;

    IF to_regclass('public.ai_prompt') IS NULL THEN
        CREATE SEQUENCE IF NOT EXISTS public.ai_prompt_seq
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
        ALTER TABLE ONLY public.ai_prompt FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.ai_prompt IS 'What a step says to a model: system instructions, a message template with {{variables}}, the expected output. Versioned; a pipeline pins the version it was saved with.';
        ALTER TABLE ONLY public.ai_prompt
            ADD CONSTRAINT ai_prompt_pkey PRIMARY KEY (prompt_id);
        ALTER TABLE ONLY public.ai_prompt
            ADD CONSTRAINT ai_prompt_prompt_uuid_key UNIQUE (prompt_uuid);
        CREATE INDEX ix_ai_prompt_connection ON public.ai_prompt USING btree (connection_id);
        CREATE INDEX ix_ai_prompt_tenant ON public.ai_prompt USING btree (tenant_id);
        CREATE TRIGGER ai_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.ai_prompt FOR EACH STATEMENT EXECUTE FUNCTION public.ai_moved_read_only();
        ALTER TABLE ONLY public.ai_prompt
            ADD CONSTRAINT ai_prompt_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.ai_model_connection(connection_id);
        ALTER TABLE public.ai_prompt ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.ai_prompt TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT ALL ON SEQUENCE public.ai_prompt_seq TO process_app;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.ai_prompt TO process_app;
    END IF;

    IF to_regclass('public.ai_prompt_version') IS NULL THEN
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
        ALTER TABLE ONLY public.ai_prompt_version
            ADD CONSTRAINT ai_prompt_version_pkey PRIMARY KEY (prompt_id, version);
        CREATE TRIGGER ai_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.ai_prompt_version FOR EACH STATEMENT EXECUTE FUNCTION public.ai_moved_read_only();
        ALTER TABLE ONLY public.ai_prompt_version
            ADD CONSTRAINT ai_prompt_version_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES public.ai_prompt(prompt_id) ON DELETE CASCADE;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.ai_prompt_version TO process_app;
    END IF;

    IF to_regclass('public.ai_prompt_run') IS NULL THEN
        CREATE SEQUENCE IF NOT EXISTS public.ai_prompt_run_seq
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
        ALTER TABLE ONLY public.ai_prompt_run FORCE ROW LEVEL SECURITY;
        ALTER TABLE ONLY public.ai_prompt_run
            ADD CONSTRAINT ai_prompt_run_pkey PRIMARY KEY (run_id);
        CREATE INDEX ix_ai_prompt_run_prompt ON public.ai_prompt_run USING btree (prompt_id, date_created DESC);
        CREATE INDEX ix_ai_prompt_run_tenant_day ON public.ai_prompt_run USING btree (tenant_id, date_created);
        CREATE UNIQUE INDEX ux_ai_prompt_run_step ON public.ai_prompt_run USING btree (job_queue_id, step_tag) WHERE (job_queue_id IS NOT NULL);
        CREATE TRIGGER ai_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.ai_prompt_run FOR EACH STATEMENT EXECUTE FUNCTION public.ai_moved_read_only();
        ALTER TABLE ONLY public.ai_prompt_run
            ADD CONSTRAINT ai_prompt_run_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES public.ai_prompt(prompt_id) ON DELETE SET NULL;
        ALTER TABLE public.ai_prompt_run ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.ai_prompt_run TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT ALL ON SEQUENCE public.ai_prompt_run_seq TO process_app;
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.ai_prompt_run TO process_app;
    END IF;

    IF to_regclass('public.analytics_query') IS NULL THEN
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
            storage_connection_id bigint,
            second_storage_connection_id bigint,
            CONSTRAINT ck_analytics_query_second_dataset_pair CHECK ((((second_connection_alias IS NULL) AND (second_dataset_path IS NULL)) OR ((second_connection_alias IS NOT NULL) AND (second_dataset_path IS NOT NULL))))
        );
        ALTER TABLE ONLY public.analytics_query FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.analytics_query IS 'A named, saved analytics query: a storage connection alias, a path inside it, and the SQL. The bucket is NOT stored -- it comes from the connection record at resolve time, so repointing a connection moves its saved queries with it.';
        COMMENT ON COLUMN public.analytics_query.second_connection_alias IS 'Connection alias of the second dataset, registered as the view dataset2. Null when the query reads one file. Set only together with second_dataset_path.';
        COMMENT ON COLUMN public.analytics_query.second_dataset_path IS 'Object key or glob of the second dataset, registered as the view dataset2. Null when the query reads one file. Set only together with second_connection_alias.';
        CREATE SEQUENCE IF NOT EXISTS public.analytics_query_seq
            START WITH 1000
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER TABLE ONLY public.analytics_query
            ADD CONSTRAINT analytics_query_pkey PRIMARY KEY (analytics_query_id);
        ALTER TABLE ONLY public.analytics_query
            ADD CONSTRAINT uk_analytics_query_id_tenant UNIQUE (analytics_query_id, tenant_id);
        CREATE INDEX idx_analytics_query_tenant_id ON public.analytics_query USING btree (tenant_id);
        CREATE TRIGGER analytics_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.analytics_query FOR EACH STATEMENT EXECUTE FUNCTION public.analytics_moved_read_only();
        ALTER TABLE public.analytics_query ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.analytics_query TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.analytics_query TO process_app;
        GRANT ALL ON SEQUENCE public.analytics_query_seq TO process_app;
    END IF;

    IF to_regclass('public.analytics_analysis') IS NULL THEN
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
            updated_by bigint,
            storage_connection_id bigint
        );
        ALTER TABLE ONLY public.analytics_analysis FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.analytics_analysis IS 'A saved Analytics Canvas analysis: a storage connection alias, a path inside it, and the dimension/measure/filter configuration as JSON. The bucket is NOT stored -- it comes from the connection record at resolve time, so repointing a connection moves its saved analyses with it.';
        COMMENT ON COLUMN public.analytics_analysis.analysis_config IS 'Dimensions, measures, aggregation, filters, sort and top-N as JSON. One column rather than twenty because the shape is still moving; the cost is that Postgres cannot answer "which analyses group by department".';
        CREATE SEQUENCE IF NOT EXISTS public.analytics_analysis_seq
            START WITH 1000
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER TABLE ONLY public.analytics_analysis
            ADD CONSTRAINT analytics_analysis_pkey PRIMARY KEY (analytics_analysis_id);
        ALTER TABLE ONLY public.analytics_analysis
            ADD CONSTRAINT uk_analytics_analysis_id_tenant UNIQUE (analytics_analysis_id, tenant_id);
        CREATE INDEX idx_analytics_analysis_tenant_id ON public.analytics_analysis USING btree (tenant_id);
        CREATE TRIGGER analytics_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.analytics_analysis FOR EACH STATEMENT EXECUTE FUNCTION public.analytics_moved_read_only();
        ALTER TABLE public.analytics_analysis ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.analytics_analysis TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.analytics_analysis TO process_app;
        GRANT ALL ON SEQUENCE public.analytics_analysis_seq TO process_app;
    END IF;

    IF to_regclass('public.analytics_dashboard') IS NULL THEN
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
        ALTER TABLE ONLY public.analytics_dashboard FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.analytics_dashboard IS 'Dashboard metadata: a name, a description and its owner. Holds no chart definition -- a widget row does that -- and does not settle whether Analytics Studio charts belong here or in the existing /reports pivot.';
        CREATE SEQUENCE IF NOT EXISTS public.analytics_dashboard_seq
            START WITH 1000
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER TABLE ONLY public.analytics_dashboard
            ADD CONSTRAINT analytics_dashboard_pkey PRIMARY KEY (analytics_dashboard_id);
        ALTER TABLE ONLY public.analytics_dashboard
            ADD CONSTRAINT uk_analytics_dashboard_id_tenant UNIQUE (analytics_dashboard_id, tenant_id);
        CREATE INDEX idx_analytics_dashboard_tenant_id ON public.analytics_dashboard USING btree (tenant_id);
        CREATE TRIGGER analytics_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.analytics_dashboard FOR EACH STATEMENT EXECUTE FUNCTION public.analytics_moved_read_only();
        ALTER TABLE public.analytics_dashboard ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.analytics_dashboard TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.analytics_dashboard TO process_app;
        GRANT ALL ON SEQUENCE public.analytics_dashboard_seq TO process_app;
    END IF;

    IF to_regclass('public.analytics_dashboard_widget') IS NULL THEN
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
        ALTER TABLE ONLY public.analytics_dashboard_widget FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.analytics_dashboard_widget IS 'One tile on a dashboard, pointing at exactly one saved analysis or one saved query. Both foreign keys name (id, tenant_id) so the database refuses a widget that references another workspace''s row.';
        COMMENT ON COLUMN public.analytics_dashboard_widget.display_order IS 'Position in the dashboard, coarsely. A finer layout belongs in widget_config until something server-side needs to read it.';
        CREATE SEQUENCE IF NOT EXISTS public.analytics_dashboard_widget_seq
            START WITH 1000
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER TABLE ONLY public.analytics_dashboard_widget
            ADD CONSTRAINT analytics_dashboard_widget_pkey PRIMARY KEY (analytics_dashboard_widget_id);
        CREATE INDEX idx_analytics_dashboard_widget_analysis ON public.analytics_dashboard_widget USING btree (analytics_analysis_id, tenant_id);
        CREATE INDEX idx_analytics_dashboard_widget_dashboard ON public.analytics_dashboard_widget USING btree (analytics_dashboard_id, tenant_id, display_order);
        CREATE INDEX idx_analytics_dashboard_widget_query ON public.analytics_dashboard_widget USING btree (analytics_query_id, tenant_id);
        CREATE TRIGGER analytics_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.analytics_dashboard_widget FOR EACH STATEMENT EXECUTE FUNCTION public.analytics_moved_read_only();
        ALTER TABLE ONLY public.analytics_dashboard_widget
            ADD CONSTRAINT fk_analytics_dashboard_widget_analysis FOREIGN KEY (analytics_analysis_id, tenant_id) REFERENCES public.analytics_analysis(analytics_analysis_id, tenant_id) ON DELETE CASCADE;
        ALTER TABLE ONLY public.analytics_dashboard_widget
            ADD CONSTRAINT fk_analytics_dashboard_widget_dashboard FOREIGN KEY (analytics_dashboard_id, tenant_id) REFERENCES public.analytics_dashboard(analytics_dashboard_id, tenant_id) ON DELETE CASCADE;
        ALTER TABLE ONLY public.analytics_dashboard_widget
            ADD CONSTRAINT fk_analytics_dashboard_widget_query FOREIGN KEY (analytics_query_id, tenant_id) REFERENCES public.analytics_query(analytics_query_id, tenant_id) ON DELETE CASCADE;
        ALTER TABLE public.analytics_dashboard_widget ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.analytics_dashboard_widget TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.analytics_dashboard_widget TO process_app;
        GRANT ALL ON SEQUENCE public.analytics_dashboard_widget_seq TO process_app;
    END IF;

    IF to_regclass('public.analytics_query_run') IS NULL THEN
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
            second_dataset_path text,
            storage_connection_id bigint,
            second_storage_connection_id bigint
        );
        ALTER TABLE ONLY public.analytics_query_run FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.analytics_query_run IS 'One row per analytics query executed: what ran, against which connection alias and path, by whom, when, for how long, how many rows and whether it failed. The module''s "who read what and when" record. Holds no credential and no resolved bucket URL, and is never pruned -- see the changeset for why.';
        COMMENT ON COLUMN public.analytics_query_run.analytics_query_id IS 'The saved query this run came from, or null for an ad-hoc query. Set null when that saved query is deleted: the bookmark goes, the record that the data was read stays.';
        COMMENT ON COLUMN public.analytics_query_run.error_message IS 'The user-facing sentence explain() produced, never the raw engine string -- engine errors quote the statement back, and the statement carries the resolved object-store location.';
        COMMENT ON COLUMN public.analytics_query_run.second_connection_alias IS 'Connection alias of the second dataset this run read as dataset2, or null when it read one file.';
        COMMENT ON COLUMN public.analytics_query_run.second_dataset_path IS 'Object key or glob of the second dataset this run read as dataset2, or null when it read one file.';
        CREATE SEQUENCE IF NOT EXISTS public.analytics_query_run_seq
            START WITH 1000
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER TABLE ONLY public.analytics_query_run
            ADD CONSTRAINT analytics_query_run_pkey PRIMARY KEY (analytics_query_run_id);
        CREATE INDEX idx_analytics_query_run_query_id ON public.analytics_query_run USING btree (analytics_query_id);
        CREATE INDEX idx_analytics_query_run_tenant_date ON public.analytics_query_run USING btree (tenant_id, date_created DESC);
        CREATE TRIGGER analytics_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.analytics_query_run FOR EACH STATEMENT EXECUTE FUNCTION public.analytics_moved_read_only();
        ALTER TABLE ONLY public.analytics_query_run
            ADD CONSTRAINT fk_analytics_query_run_query FOREIGN KEY (analytics_query_id) REFERENCES public.analytics_query(analytics_query_id) ON DELETE SET NULL;
        ALTER TABLE public.analytics_query_run ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.analytics_query_run TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.analytics_query_run TO process_app;
        GRANT ALL ON SEQUENCE public.analytics_query_run_seq TO process_app;
    END IF;

    IF to_regclass('public.billing_account') IS NULL THEN
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
        ALTER TABLE ONLY public.billing_account FORCE ROW LEVEL SECURITY;
        COMMENT ON COLUMN public.billing_account.tax_id IS 'VAT/GST number; tax is applied only when this and a rate are set';
        CREATE SEQUENCE public.billing_account_billing_account_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER SEQUENCE public.billing_account_billing_account_id_seq OWNED BY public.billing_account.billing_account_id;
        ALTER TABLE ONLY public.billing_account ALTER COLUMN billing_account_id SET DEFAULT nextval('public.billing_account_billing_account_id_seq'::regclass);
        ALTER TABLE ONLY public.billing_account
            ADD CONSTRAINT billing_account_pkey PRIMARY KEY (billing_account_id);
        ALTER TABLE ONLY public.billing_account
            ADD CONSTRAINT billing_account_tenant_id_key UNIQUE (tenant_id);
        CREATE TRIGGER billing_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.billing_account FOR EACH STATEMENT EXECUTE FUNCTION public.billing_moved_read_only();
        ALTER TABLE public.billing_account ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.billing_account TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.billing_account TO process_app;
        GRANT ALL ON SEQUENCE public.billing_account_billing_account_id_seq TO process_app;
    END IF;

    IF to_regclass('public.billing_number_counter') IS NULL THEN
        CREATE TABLE public.billing_number_counter (
            base character varying(64) NOT NULL,
            last_value integer NOT NULL,
            updated_at timestamp with time zone DEFAULT now() NOT NULL
        );
        ALTER TABLE ONLY public.billing_number_counter
            ADD CONSTRAINT billing_number_counter_pkey PRIMARY KEY (base);
        CREATE TRIGGER billing_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.billing_number_counter FOR EACH STATEMENT EXECUTE FUNCTION public.billing_moved_read_only();
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.billing_number_counter TO process_app;
    END IF;

    IF to_regclass('public.billing_document') IS NULL THEN
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
        ALTER TABLE ONLY public.billing_document FORCE ROW LEVEL SECURITY;
        COMMENT ON COLUMN public.billing_document.kind IS 'invoice | credit_note | receipt | statement | payment_slip';
        CREATE SEQUENCE public.billing_document_billing_document_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER SEQUENCE public.billing_document_billing_document_id_seq OWNED BY public.billing_document.billing_document_id;
        ALTER TABLE ONLY public.billing_document ALTER COLUMN billing_document_id SET DEFAULT nextval('public.billing_document_billing_document_id_seq'::regclass);
        ALTER TABLE ONLY public.billing_document
            ADD CONSTRAINT billing_document_pkey PRIMARY KEY (billing_document_id);
        CREATE INDEX billing_document_tenant ON public.billing_document USING btree (tenant_id, issued_at);
        CREATE UNIQUE INDEX ux_billing_document_number ON public.billing_document USING btree (number) WHERE (number IS NOT NULL);
        CREATE TRIGGER billing_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.billing_document FOR EACH STATEMENT EXECUTE FUNCTION public.billing_moved_read_only();
        ALTER TABLE public.billing_document ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.billing_document TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.billing_document TO process_app;
        GRANT ALL ON SEQUENCE public.billing_document_billing_document_id_seq TO process_app;
    END IF;

    IF to_regclass('public.invoice_line') IS NULL THEN
        CREATE TABLE public.invoice_line (
            invoice_line_id bigint NOT NULL,
            invoice_id bigint NOT NULL,
            sort integer DEFAULT 0 NOT NULL,
            meter character varying(64),
            description character varying(300) NOT NULL,
            quantity numeric(24,6) DEFAULT 0 NOT NULL,
            unit character varying(24),
            per integer DEFAULT 1 NOT NULL,
            unit_price numeric(18,8) DEFAULT 0 NOT NULL,
            amount numeric(18,5) DEFAULT 0 NOT NULL,
            period_label character varying(32),
            manual boolean DEFAULT false NOT NULL,
            included_quantity numeric(24,6),
            billable_quantity numeric(24,6),
            pricing_detail text,
            tenant_id bigint NOT NULL
        );
        ALTER TABLE ONLY public.invoice_line FORCE ROW LEVEL SECURITY;
        CREATE SEQUENCE public.invoice_line_invoice_line_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER SEQUENCE public.invoice_line_invoice_line_id_seq OWNED BY public.invoice_line.invoice_line_id;
        ALTER TABLE ONLY public.invoice_line ALTER COLUMN invoice_line_id SET DEFAULT nextval('public.invoice_line_invoice_line_id_seq'::regclass);
        ALTER TABLE ONLY public.invoice_line
            ADD CONSTRAINT invoice_line_pkey PRIMARY KEY (invoice_line_id);
        CREATE INDEX invoice_line_invoice_tenant ON public.invoice_line USING btree (invoice_id, tenant_id, sort);
        CREATE TRIGGER billing_moved_read_only BEFORE INSERT OR DELETE OR UPDATE OR TRUNCATE ON public.invoice_line FOR EACH STATEMENT EXECUTE FUNCTION public.billing_moved_read_only();
        ALTER TABLE ONLY public.invoice_line
            ADD CONSTRAINT fk_invoice_line_invoice_tenant FOREIGN KEY (invoice_id, tenant_id) REFERENCES public.invoice(invoice_id, tenant_id) ON DELETE CASCADE;
        ALTER TABLE ONLY public.invoice_line
            ADD CONSTRAINT invoice_line_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoice(invoice_id) ON DELETE CASCADE;
        ALTER TABLE public.invoice_line ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.invoice_line TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.invoice_line TO process_app;
        GRANT ALL ON SEQUENCE public.invoice_line_invoice_line_id_seq TO process_app;
    END IF;

    -- Not built by the changelog: V54 dropped the empty one a new database gets, and this sequence (owned by nothing) stayed.
    IF to_regclass('public.document_converter_task_id_seq') IS NULL THEN
        CREATE SEQUENCE IF NOT EXISTS public.document_converter_task_id_seq
            START WITH 1000
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        GRANT ALL ON SEQUENCE public.document_converter_task_id_seq TO process_app;
    END IF;

    -- The two renamed copies exist only where V52 / V54 found the original renamed by the move script (MARK_RAN), never on a
    -- database built from the changelog, where V52 / V54 dropped the empty originals: only there do they come back.
    IF to_regclass('public.notification_moved_mig21') IS NULL AND to_regclass('public.notification') IS NULL
            AND EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '52.0-notification-moves-to-notifications-db' AND exectype = 'MARK_RAN') THEN
        CREATE TABLE public.notification_moved_mig21 (
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
        ALTER TABLE ONLY public.notification_moved_mig21 FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.notification_moved_mig21 IS 'An in-app message for one recipient -- title, body, severity, read state and a link to the screen it refers to.';
        CREATE SEQUENCE public.notification_notification_id_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1;
        ALTER SEQUENCE public.notification_notification_id_seq OWNED BY public.notification_moved_mig21.notification_id;
        ALTER TABLE ONLY public.notification_moved_mig21 ALTER COLUMN notification_id SET DEFAULT nextval('public.notification_notification_id_seq'::regclass);
        ALTER TABLE ONLY public.notification_moved_mig21
            ADD CONSTRAINT notification_pkey PRIMARY KEY (notification_id);
        CREATE INDEX idx_notification_recipient ON public.notification_moved_mig21 USING btree (recipient_user_id);
        CREATE INDEX idx_notification_recipient_read ON public.notification_moved_mig21 USING btree (recipient_user_id, is_read);
        CREATE INDEX idx_notification_tenant_id ON public.notification_moved_mig21 USING btree (tenant_id);
        ALTER TABLE public.notification_moved_mig21 ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.notification_moved_mig21 TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.notification_moved_mig21 TO process_app;
        GRANT ALL ON SEQUENCE public.notification_notification_id_seq TO process_app;
    END IF;

    IF to_regclass('public.document_converter_task_moved_mig41') IS NULL AND to_regclass('public.document_converter_task') IS NULL
            AND EXISTS (SELECT 1 FROM public.databasechangelog WHERE id = '54.0-document-converter-task-moves-to-media-db' AND exectype = 'MARK_RAN') THEN
        CREATE TABLE public.document_converter_task_moved_mig41 (
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
        ALTER TABLE ONLY public.document_converter_task_moved_mig41 FORCE ROW LEVEL SECURITY;
        COMMENT ON TABLE public.document_converter_task_moved_mig41 IS 'A completed file conversion: the input and output formats, sizes, and the storage keys of both, so a converted file stays retrievable after the tab that made it is closed.';
        ALTER TABLE ONLY public.document_converter_task_moved_mig41
            ADD CONSTRAINT document_converter_task_pkey PRIMARY KEY (document_converter_task_id);
        CREATE INDEX idx_document_converter_task_tenant_id ON public.document_converter_task_moved_mig41 USING btree (tenant_id);
        ALTER TABLE public.document_converter_task_moved_mig41 ENABLE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation ON public.document_converter_task_moved_mig41 TO process_app USING (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text))))) WITH CHECK (((tenant_id = ( SELECT (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::bigint AS "nullif")) OR (tenant_id >= ( SELECT
                CASE
                    WHEN (current_setting('app.all_tenants'::text, true) = 'on'::text) THEN '-9223372036854775808'::bigint
                    ELSE NULL::bigint
                END AS "case")) OR ((tenant_id IS NULL) AND ( SELECT (current_setting('app.all_tenants'::text, true) = 'on'::text)))));
        GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE public.document_converter_task_moved_mig41 TO process_app;
    END IF;
END
$$;
