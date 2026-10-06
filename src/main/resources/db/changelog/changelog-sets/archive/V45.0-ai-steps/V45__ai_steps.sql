-- An AI step on a pipeline: a field of type 'ai' names a prompt, maps the prompt's variables
-- to earlier fields' tags, and writes the answer to its own tag before the task is dispatched.
ALTER TABLE pipeline_field ADD COLUMN IF NOT EXISTS prompt_id BIGINT REFERENCES ai_prompt(prompt_id);
ALTER TABLE pipeline_field ADD COLUMN IF NOT EXISTS variable_map TEXT;
ALTER TABLE pipeline_field ADD COLUMN IF NOT EXISTS on_error VARCHAR(16);
CREATE INDEX IF NOT EXISTS ix_pipeline_field_prompt ON pipeline_field(prompt_id) WHERE prompt_id IS NOT NULL;
COMMENT ON COLUMN pipeline_field.prompt_id IS 'field_type = ai: the prompt this step runs';
COMMENT ON COLUMN pipeline_field.variable_map IS 'field_type = ai: JSON {promptVariable: sourceTagKey}';
COMMENT ON COLUMN pipeline_field.on_error IS 'field_type = ai: fail (the run) | continue (empty tag)';
