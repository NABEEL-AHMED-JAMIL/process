-- media_db, Media & Documents' own database (MIG-41).
--
-- The one source of this table's shape: the changelog runs it on a fresh database, and
-- scripts/media/move-to-media-db.sh runs it before copying the rows over.
--
-- tenant_id is NOT NULL (P2) and a plain bigint: tenant lives in another database, so the FK
-- that etl_job had is gone (the Tier 2 disposition). 0 is the platform scope, for a conversion a
-- platform admin ran with no tenant of their own -- as notifications_db does. The primary key is
-- also declared UNIQUE, as it always was; the migration rules say keep that, not tidy it away.
CREATE SEQUENCE document_converter_task_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE document_converter_task (
    document_converter_task_id BIGINT        NOT NULL DEFAULT nextval('document_converter_task_id_seq'),
    tenant_id                  BIGINT        NOT NULL CHECK (tenant_id >= 0),
    task_name                  VARCHAR(255)  NOT NULL,
    input_file_name            VARCHAR(255)  NOT NULL,
    input_format               VARCHAR(255)  NOT NULL,
    input_content_type         VARCHAR(255),
    input_file_size            BIGINT,
    output_format              VARCHAR(255)  NOT NULL,
    output_file_name           VARCHAR(255),
    output_content_type        VARCHAR(255),
    output_file_size           BIGINT,
    bucket_name                VARCHAR(255)  NOT NULL,
    target_folder              VARCHAR(255),
    input_storage_key          VARCHAR(255)  NOT NULL,
    output_storage_key         VARCHAR(255)  NOT NULL,
    status                     VARCHAR(255)  NOT NULL,
    date_created               TIMESTAMP     NOT NULL,
    CONSTRAINT document_converter_task_pkey PRIMARY KEY (document_converter_task_id),
    CONSTRAINT uk_document_converter_task_id UNIQUE (document_converter_task_id)
);

ALTER SEQUENCE document_converter_task_id_seq OWNED BY document_converter_task.document_converter_task_id;

CREATE INDEX idx_document_converter_task_tenant_id ON document_converter_task (tenant_id);
