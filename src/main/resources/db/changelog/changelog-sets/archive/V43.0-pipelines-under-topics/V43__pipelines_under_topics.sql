-- Pipelines belong to a topic; the "task form" tables are the pipeline tables.
--
-- task_form described what a pipeline expects (its id and the fields a task on it fills in),
-- and a task carried two independent pointers: a topic (source_task_type_id) and a pipeline
-- (pipeline_id, a free string matched against task_form). Nothing tied the two together, so
-- whoever created a task had to know that F768947 publishes on medaxis-claims-intake. The
-- worker side always assumed the opposite -- one consumer per topic, dispatching on pipelineId
-- -- so the pipeline now names its topic, a topic carries many pipelines, and a task picks a
-- topic and then one of that topic's pipelines.
--
-- Naming: the pipeline's public id (F768947, what the worker routes on and what source_task
-- carries as pipeline_id) keeps the name pipeline_id. The surrogate key is pipeline_key so the
-- two cannot be confused in a join.

ALTER TABLE task_form RENAME TO pipeline;
ALTER TABLE task_form_field RENAME TO pipeline_field;
ALTER SEQUENCE task_form_source_seq RENAME TO pipeline_source_seq;

ALTER TABLE pipeline RENAME COLUMN task_form_id TO pipeline_key;
ALTER TABLE pipeline RENAME COLUMN form_name TO pipeline_name;
ALTER TABLE pipeline RENAME COLUMN form_status TO status;
ALTER TABLE pipeline_field RENAME COLUMN task_form_field_id TO pipeline_field_id;
ALTER TABLE pipeline_field RENAME COLUMN task_form_id TO pipeline_key;

ALTER INDEX task_form_pkey RENAME TO pipeline_pkey;
ALTER INDEX task_form_field_pkey RENAME TO pipeline_field_pkey;
ALTER INDEX ux_task_form_pipeline_tenant RENAME TO ux_pipeline_id_tenant;
ALTER INDEX ix_task_form_field_form RENAME TO ix_pipeline_field_pipeline;
ALTER INDEX ix_task_form_tenant RENAME TO ix_pipeline_tenant;

-- The topic a pipeline publishes on. Nullable: a pipeline from before this change has none
-- until somebody assigns one, and the console says so rather than guessing.
ALTER TABLE pipeline ADD COLUMN IF NOT EXISTS source_task_type_id BIGINT
    REFERENCES source_task_type (source_task_type_id);
CREATE INDEX IF NOT EXISTS ix_pipeline_topic ON pipeline (source_task_type_id);

COMMENT ON TABLE pipeline IS
    'A pipeline a task can run: its public id (what the worker routes on), the topic it publishes on, and the fields a task on it fills in.';
COMMENT ON TABLE pipeline_field IS
    'One field of a pipeline: the XML tag it fills, and how it is presented to whoever fills it in.';
COMMENT ON COLUMN pipeline.pipeline_key IS 'Surrogate key. pipeline_id is the public id the worker routes on.';
COMMENT ON COLUMN pipeline.source_task_type_id IS 'The topic this pipeline publishes on; null only for a pipeline defined before topics were required.';
