-- V102 (MIG-29, MIG-164; P2 / DEF-034): tenant_id on the five of the nine tenantless tables that are still process's.
--
-- Each inherits its tenant through exactly one parent foreign key, which stays; tenant_id goes beside it:
--   job_queue           <- source_job  (job_id)
--   scheduler           <- source_job  (job_id)
--   job_audit_logs      <- job_queue   (job_queue_id), so after job_queue
--   source_task_payload <- source_task (payload_id)
--   pipeline_field      <- pipeline    (pipeline_key)
-- The other four: invoice_line got its column in V59 and lives in billing_db; user_page_access got its in V65;
-- ai_prompt_version lives in ai_db (V63) and is ai-service's to change; tenant_request is pre-tenant by design
-- (created_tenant_id is the tenant it becomes).
--
-- Backfilled by JOIN, then NOT NULL, a foreign key to tenant, and a composite foreign key (parent id, tenant_id)
-- onto the parent's (id, tenant_id): that one makes "the row's tenant is its parent's" the database's invariant
-- rather than a reconciliation someone has to remember to run, and ON UPDATE CASCADE carries a parent moved to
-- another tenant down the chain (source_job -> job_queue -> job_audit_logs). Existing foreign keys and their
-- cascades (pipeline_field ON DELETE CASCADE) are untouched.
--
-- A trigger fills tenant_id from the parent when a writer leaves it out -- the application sets it on every
-- insert, but the column is derived data and native writers (the OpenSearch audit sync, fixtures, psql) should
-- not each have to know how. When a parent leaves this database the trigger and the composite key go with it,
-- and the writer supplies the tenant.

ALTER TABLE public.source_job ADD CONSTRAINT ux_source_job_id_tenant UNIQUE (job_id, tenant_id);
ALTER TABLE public.source_task ADD CONSTRAINT ux_source_task_id_tenant UNIQUE (task_detail_id, tenant_id);
ALTER TABLE public.pipeline ADD CONSTRAINT ux_pipeline_key_tenant UNIQUE (pipeline_key, tenant_id);

-- job_queue
ALTER TABLE public.job_queue ADD COLUMN tenant_id BIGINT;
UPDATE public.job_queue q SET tenant_id = j.tenant_id FROM public.source_job j WHERE j.job_id = q.job_id AND q.tenant_id IS NULL;
ALTER TABLE public.job_queue ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE public.job_queue ADD CONSTRAINT fk_job_queue_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant (tenant_id);
ALTER TABLE public.job_queue ADD CONSTRAINT fk_job_queue_job_tenant
    FOREIGN KEY (job_id, tenant_id) REFERENCES public.source_job (job_id, tenant_id) ON UPDATE CASCADE;
ALTER TABLE public.job_queue ADD CONSTRAINT ux_job_queue_id_tenant UNIQUE (job_queue_id, tenant_id);
CREATE INDEX idx_job_queue_tenant ON public.job_queue (tenant_id);

-- scheduler
ALTER TABLE public.scheduler ADD COLUMN tenant_id BIGINT;
UPDATE public.scheduler s SET tenant_id = j.tenant_id FROM public.source_job j WHERE j.job_id = s.job_id AND s.tenant_id IS NULL;
ALTER TABLE public.scheduler ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE public.scheduler ADD CONSTRAINT fk_scheduler_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant (tenant_id);
ALTER TABLE public.scheduler ADD CONSTRAINT fk_scheduler_job_tenant
    FOREIGN KEY (job_id, tenant_id) REFERENCES public.source_job (job_id, tenant_id) ON UPDATE CASCADE;
CREATE INDEX idx_scheduler_tenant ON public.scheduler (tenant_id);

-- job_audit_logs, from the run it belongs to
ALTER TABLE public.job_audit_logs ADD COLUMN tenant_id BIGINT;
UPDATE public.job_audit_logs a SET tenant_id = q.tenant_id FROM public.job_queue q WHERE q.job_queue_id = a.job_queue_id AND a.tenant_id IS NULL;
ALTER TABLE public.job_audit_logs ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE public.job_audit_logs ADD CONSTRAINT fk_job_audit_logs_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant (tenant_id);
ALTER TABLE public.job_audit_logs ADD CONSTRAINT fk_job_audit_logs_run_tenant
    FOREIGN KEY (job_queue_id, tenant_id) REFERENCES public.job_queue (job_queue_id, tenant_id) ON UPDATE CASCADE;
CREATE INDEX idx_job_audit_logs_tenant ON public.job_audit_logs (tenant_id);

-- source_task_payload. payload_id is nullable: Hibernate inserts a task's tags first and points them at the task
-- after, so the application sets tenant_id on the tag itself (SourceTaskServiceImpl).
ALTER TABLE public.source_task_payload ADD COLUMN tenant_id BIGINT;
UPDATE public.source_task_payload p SET tenant_id = t.tenant_id FROM public.source_task t WHERE t.task_detail_id = p.payload_id AND p.tenant_id IS NULL;
ALTER TABLE public.source_task_payload ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE public.source_task_payload ADD CONSTRAINT fk_source_task_payload_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant (tenant_id);
ALTER TABLE public.source_task_payload ADD CONSTRAINT fk_source_task_payload_task_tenant
    FOREIGN KEY (payload_id, tenant_id) REFERENCES public.source_task (task_detail_id, tenant_id) ON UPDATE CASCADE;
CREATE INDEX idx_source_task_payload_tenant ON public.source_task_payload (tenant_id);

-- pipeline_field
ALTER TABLE public.pipeline_field ADD COLUMN tenant_id BIGINT;
UPDATE public.pipeline_field f SET tenant_id = p.tenant_id FROM public.pipeline p WHERE p.pipeline_key = f.pipeline_key AND f.tenant_id IS NULL;
ALTER TABLE public.pipeline_field ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE public.pipeline_field ADD CONSTRAINT fk_pipeline_field_tenant FOREIGN KEY (tenant_id) REFERENCES public.tenant (tenant_id);
ALTER TABLE public.pipeline_field ADD CONSTRAINT fk_pipeline_field_pipeline_tenant
    FOREIGN KEY (pipeline_key, tenant_id) REFERENCES public.pipeline (pipeline_key, tenant_id) ON UPDATE CASCADE;
CREATE INDEX idx_pipeline_field_tenant ON public.pipeline_field (tenant_id);

-- The default: the parent's tenant, when a writer leaves tenant_id out.
CREATE OR REPLACE FUNCTION public.tenant_id_from_parent() RETURNS trigger AS $$
DECLARE
    parent_table  text := TG_ARGV[0];
    parent_key    text := TG_ARGV[1];
    child_column  text := TG_ARGV[2];
    parent_id     bigint;
BEGIN
    IF NEW.tenant_id IS NOT NULL THEN
        RETURN NEW;
    END IF;
    EXECUTE format('SELECT ($1).%I', child_column) INTO parent_id USING NEW;
    IF parent_id IS NOT NULL THEN
        EXECUTE format('SELECT tenant_id FROM public.%I WHERE %I = $1', parent_table, parent_key) INTO NEW.tenant_id USING parent_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER job_queue_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_id ON public.job_queue
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('source_job', 'job_id', 'job_id');
CREATE TRIGGER scheduler_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_id ON public.scheduler
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('source_job', 'job_id', 'job_id');
CREATE TRIGGER job_audit_logs_tenant_id_from_parent BEFORE INSERT OR UPDATE OF job_queue_id ON public.job_audit_logs
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('job_queue', 'job_queue_id', 'job_queue_id');
CREATE TRIGGER source_task_payload_tenant_id_from_parent BEFORE INSERT OR UPDATE OF payload_id ON public.source_task_payload
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('source_task', 'task_detail_id', 'payload_id');
CREATE TRIGGER pipeline_field_tenant_id_from_parent BEFORE INSERT OR UPDATE OF pipeline_key ON public.pipeline_field
    FOR EACH ROW EXECUTE PROCEDURE public.tenant_id_from_parent('pipeline', 'pipeline_key', 'pipeline_key');

COMMENT ON COLUMN public.job_queue.tenant_id IS 'The job''s tenant (source_job.tenant_id), held to it by fk_job_queue_job_tenant (MIG-29/164).';
COMMENT ON COLUMN public.scheduler.tenant_id IS 'The job''s tenant (source_job.tenant_id), held to it by fk_scheduler_job_tenant (MIG-29/164).';
COMMENT ON COLUMN public.job_audit_logs.tenant_id IS 'The run''s tenant (job_queue.tenant_id), held to it by fk_job_audit_logs_run_tenant (MIG-29/164).';
COMMENT ON COLUMN public.source_task_payload.tenant_id IS 'The task''s tenant (source_task.tenant_id), held to it by fk_source_task_payload_task_tenant (MIG-29/164).';
COMMENT ON COLUMN public.pipeline_field.tenant_id IS 'The pipeline''s tenant (pipeline.tenant_id), held to it by fk_pipeline_field_pipeline_tenant (MIG-29/164).';
