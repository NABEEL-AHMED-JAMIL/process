-- A form definition describes the payload one pipeline expects, so a task can be configured by
-- filling in labelled fields instead of writing XML by hand.
--
-- It deliberately does not store the payload. The tags a task already keeps in
-- source_task_payload remain the single source of truth for what a task sends; a definition only
-- says what those tags mean -- their label, type, whether they are required, and where they sit
-- in the document. That way a task configured through a form and one configured by hand are the
-- same task, and removing a definition never changes what any task does.

CREATE SEQUENCE IF NOT EXISTS task_form_source_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS task_form (
    task_form_id      BIGINT PRIMARY KEY,
    -- The pipeline decides the payload's shape: the worker routes on pipelineId, and each
    -- pipeline parses its own tags. Anchoring here rather than on a task type means every task
    -- on that pipeline gets the same form.
    pipeline_id       VARCHAR(120) NOT NULL,
    form_name         VARCHAR(255) NOT NULL,
    description       TEXT,
    -- Null means the definition is available to every tenant; a value scopes it to one.
    tenant_id         BIGINT,
    form_status       VARCHAR(24)  NOT NULL DEFAULT 'Active',
    date_created      TIMESTAMP    NOT NULL DEFAULT now(),
    created_by        BIGINT
);

CREATE TABLE IF NOT EXISTS task_form_field (
    task_form_field_id BIGINT PRIMARY KEY,
    task_form_id       BIGINT       NOT NULL REFERENCES task_form (task_form_id) ON DELETE CASCADE,
    -- The XML tag this field fills in, and the tag it nests under. Together these are what the
    -- existing tag rows carry, which is how a filled-in form becomes an ordinary task.
    tag_key            VARCHAR(190) NOT NULL,
    tag_parent         VARCHAR(190),
    label              VARCHAR(255) NOT NULL,
    field_type         VARCHAR(32)  NOT NULL DEFAULT 'text',
    required           BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value      TEXT,
    help_text          TEXT,
    -- Newline-separated choices for a select; ignored by every other type.
    field_options      TEXT,
    position           INT          NOT NULL DEFAULT 0
);

-- Two forms for one pipeline in one tenant would leave the task screen with no way to choose.
CREATE UNIQUE INDEX IF NOT EXISTS ux_task_form_pipeline_tenant
    ON task_form (pipeline_id, COALESCE(tenant_id, -1))
    WHERE form_status <> 'Delete';

CREATE INDEX IF NOT EXISTS ix_task_form_field_form ON task_form_field (task_form_id);
CREATE INDEX IF NOT EXISTS ix_task_form_tenant ON task_form (tenant_id);

COMMENT ON TABLE task_form IS
    'Describes the payload a pipeline expects, so tasks can be configured by form rather than by hand-written XML. Holds no payload itself.';
COMMENT ON TABLE task_form_field IS
    'One field of a task form: the XML tag it fills, and how it is presented to whoever fills it in.';
