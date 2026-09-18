-- AI prompts in pipelines, phase 1: an "agent" (one row = provider + key + one fixed prompt)
-- becomes a model connection (where a prompt runs) and a prompt (what it says, with variables
-- and an expected output), and every run -- a Try it or a pipeline step -- is a row.

CREATE SEQUENCE IF NOT EXISTS ai_model_connection_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ai_model_connection (
    connection_id       BIGINT PRIMARY KEY DEFAULT nextval('ai_model_connection_seq'),
    tenant_id           BIGINT REFERENCES tenant(tenant_id),
    name                VARCHAR(255) NOT NULL,
    provider            VARCHAR(64)  NOT NULL,
    api_endpoint        VARCHAR(500),
    api_key             VARCHAR(1000),
    default_model       VARCHAR(255) NOT NULL,
    is_default          BOOLEAN      NOT NULL DEFAULT FALSE,
    max_concurrency     INTEGER      NOT NULL DEFAULT 4,
    daily_token_budget  BIGINT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'Active',
    last_tested_at      TIMESTAMP,
    last_test_ok        BOOLEAN,
    last_test_message   TEXT,
    models_listed       TEXT,
    date_created        TIMESTAMP    NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_by          BIGINT
);
CREATE INDEX IF NOT EXISTS ix_ai_model_connection_tenant ON ai_model_connection(tenant_id);
COMMENT ON TABLE ai_model_connection IS 'Where a prompt runs: provider, endpoint, encrypted key, default model, caps. One default per workspace.';

CREATE SEQUENCE IF NOT EXISTS ai_prompt_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ai_prompt (
    prompt_id           BIGINT PRIMARY KEY DEFAULT nextval('ai_prompt_seq'),
    prompt_uuid         VARCHAR(36)  NOT NULL UNIQUE,
    tenant_id           BIGINT REFERENCES tenant(tenant_id),
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    connection_id       BIGINT REFERENCES ai_model_connection(connection_id),
    model               VARCHAR(255),
    system_instructions TEXT,
    user_template       TEXT         NOT NULL,
    variables           TEXT         NOT NULL DEFAULT '[]',
    output_mode         VARCHAR(16)  NOT NULL DEFAULT 'text',
    output_schema       TEXT,
    temperature         NUMERIC(3,2),
    max_tokens          INTEGER,
    tags                VARCHAR(500),
    version             INTEGER      NOT NULL DEFAULT 1,
    status              VARCHAR(32)  NOT NULL DEFAULT 'Inactive',
    date_created        TIMESTAMP    NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_by          BIGINT
);
CREATE INDEX IF NOT EXISTS ix_ai_prompt_tenant ON ai_prompt(tenant_id);
CREATE INDEX IF NOT EXISTS ix_ai_prompt_connection ON ai_prompt(connection_id);
COMMENT ON TABLE ai_prompt IS 'What a step says to a model: system instructions, a message template with {{variables}}, the expected output. Versioned; a pipeline pins the version it was saved with.';

-- Every saved version, so a run and a job history can show exactly what ran.
CREATE TABLE IF NOT EXISTS ai_prompt_version (
    prompt_id           BIGINT  NOT NULL REFERENCES ai_prompt(prompt_id) ON DELETE CASCADE,
    version             INTEGER NOT NULL,
    connection_id       BIGINT,
    model               VARCHAR(255),
    system_instructions TEXT,
    user_template       TEXT    NOT NULL,
    variables           TEXT    NOT NULL DEFAULT '[]',
    output_mode         VARCHAR(16) NOT NULL DEFAULT 'text',
    output_schema       TEXT,
    temperature         NUMERIC(3,2),
    max_tokens          INTEGER,
    date_created        TIMESTAMP NOT NULL DEFAULT now(),
    created_by          BIGINT,
    PRIMARY KEY (prompt_id, version)
);

CREATE SEQUENCE IF NOT EXISTS ai_prompt_run_seq START WITH 1000 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ai_prompt_run (
    run_id              BIGINT PRIMARY KEY DEFAULT nextval('ai_prompt_run_seq'),
    tenant_id           BIGINT,
    prompt_id           BIGINT REFERENCES ai_prompt(prompt_id) ON DELETE SET NULL,
    prompt_version      INTEGER,
    connection_id       BIGINT,
    kind                VARCHAR(16)  NOT NULL,      -- try | run
    job_queue_id        BIGINT,
    step_tag            VARCHAR(255),
    rendered_input      TEXT,
    output              TEXT,
    tokens_in           INTEGER,
    tokens_out          INTEGER,
    latency_ms          INTEGER,
    attempts            INTEGER      NOT NULL DEFAULT 1,
    status              VARCHAR(16)  NOT NULL,      -- ok | failed
    error               TEXT,
    date_created        TIMESTAMP    NOT NULL DEFAULT now(),
    created_by          BIGINT
);
CREATE INDEX IF NOT EXISTS ix_ai_prompt_run_prompt ON ai_prompt_run(prompt_id, date_created DESC);
CREATE INDEX IF NOT EXISTS ix_ai_prompt_run_tenant_day ON ai_prompt_run(tenant_id, date_created);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_prompt_run_step ON ai_prompt_run(job_queue_id, step_tag) WHERE job_queue_id IS NOT NULL;

-- Migrate agents: one connection per distinct (tenant, provider, endpoint, key), one prompt
-- per agent. Instructions become the system text; the template is the message itself.
INSERT INTO ai_model_connection (tenant_id, name, provider, api_endpoint, api_key, default_model, is_default, status, created_by)
SELECT a.tenant_id,
       a.provider || ' · ' || coalesce(nullif(a.api_endpoint, ''), 'default endpoint'),
       a.provider, nullif(a.api_endpoint, ''), a.api_key, min(a.model), false, 'Active', min(a.created_by)
FROM ai_agent a
WHERE a.status <> 'Delete'
GROUP BY a.tenant_id, a.provider, nullif(a.api_endpoint, ''), a.api_key;

INSERT INTO ai_prompt (prompt_uuid, tenant_id, name, description, connection_id, model, system_instructions, user_template,
                       variables, output_mode, tags, version, status, date_created, created_by, updated_by)
SELECT coalesce(a.tool_uuid, md5(random()::text || a.ai_agent_id::text)::uuid::text),
       a.tenant_id, a.agent_name, a.description, c.connection_id, a.model, a.instructions, '{{text}}',
       '[{"name":"text","type":"text","required":true,"sample":""}]',
       CASE WHEN a.json_mode THEN 'json' ELSE 'text' END,
       a.target_file_types, 1,
       CASE WHEN a.status = 'Active' THEN 'Active' ELSE 'Inactive' END,
       coalesce(a.date_created, now()), a.created_by, a.updated_by
FROM ai_agent a
JOIN ai_model_connection c
  ON c.tenant_id IS NOT DISTINCT FROM a.tenant_id AND c.provider = a.provider
 AND c.api_endpoint IS NOT DISTINCT FROM nullif(a.api_endpoint, '') AND c.api_key IS NOT DISTINCT FROM a.api_key
WHERE a.status <> 'Delete';

INSERT INTO ai_prompt_version (prompt_id, version, connection_id, model, system_instructions, user_template, variables, output_mode, created_by)
SELECT prompt_id, version, connection_id, model, system_instructions, user_template, variables, output_mode, created_by FROM ai_prompt;

-- The one workspace connection becomes its default.
UPDATE ai_model_connection c SET is_default = true
WHERE c.tenant_id IS NOT NULL
  AND (SELECT count(*) FROM ai_model_connection d WHERE d.tenant_id = c.tenant_id) = 1;

-- Access profiles: the page is "Prompts" now; grants follow.
UPDATE page_access_profile_page SET page_key = 'ai-prompts' WHERE page_key = 'ai-agents';
UPDATE user_page_access SET page_key = 'ai-prompts' WHERE page_key = 'ai-agents';
