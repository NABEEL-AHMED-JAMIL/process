-- Descriptions for every table, written from what each one actually holds.
-- Tenancy note: tables carrying tenant_id are scoped by the Hibernate "tenantFilter";
-- the rest inherit their tenant through their parent row, which is why a query that
-- reaches them by id must join the parent to stay inside the caller's tenant.

COMMENT ON TABLE tenant IS
  'An isolated workspace. Every tenant-scoped table keys back to this one, and nothing crosses between tenants except for a platform admin.';

COMMENT ON TABLE app_user IS
  'A person who can sign in, with their role (PLATFORM_ADMIN, TENANT_ADMIN, TENANT_USER) and the tenant they belong to. Soft-deleted: status Delete rather than a removed row.';

COMMENT ON TABLE source_task IS
  'One unit of ETL work: where it reads, where it writes, and the task type that consumes it. Jobs point at a task; deleting a task cascades its jobs to Delete.';

COMMENT ON TABLE source_task_type IS
  'A consumer class -- the service that runs a task and the Kafka topic and partitions it listens on, stored as topic=name&partitions=[n].';

COMMENT ON TABLE source_task_payload IS
  'The tag tree of a task''s XML payload, one row per tag. payload_id is a foreign key to source_task.task_detail_id despite the name, so it inherits its tenant from the task.';

COMMENT ON TABLE source_job IS
  'A task bound to a timetable. Holds run/execution settings, priority and the three email-notification flags; Auto jobs have a matching scheduler row, Manual ones run on demand. Soft-deleted via job_status.';

COMMENT ON TABLE scheduler IS
  'The timetable for one Auto job: frequency, interval, start and end dates, and the computed next_run_at the dispatcher polls. One row per job, no tenant_id -- it inherits from source_job.';

COMMENT ON TABLE job_queue IS
  'One execution of a job -- queued, started, ended, final status and message, plus the flags recording whether it was started by hand and whether it reached the queue. Kept after its job is deleted, which is why run history survives.';

COMMENT ON TABLE job_audit_logs IS
  'Log lines a worker reported for one run, keyed by job_queue_id. Mirrored into OpenSearch; the API merges both sources and de-duplicates.';

COMMENT ON TABLE storage_connection IS
  'Credentials and settings for one storage backend (S3, MinIO, Azure, FTP, FTPS). The object browser and every bucket path in the app resolve through these.';

COMMENT ON TABLE kafka_connection_profile IS
  'How to reach a Kafka cluster: brokers, security protocol, SASL details and the bucket paths of any TLS keystore and truststore. Secrets are stored encrypted and never returned to the UI.';

COMMENT ON TABLE tenant_task_type_kafka_route IS
  'Routes one tenant''s task type to a specific Kafka profile, so two tenants sharing a task type can publish to different clusters.';

COMMENT ON TABLE lookup_data IS
  'Shared key/value reference data used across screens. Self-referencing: a row with a parent is a sub-lookup of it.';

COMMENT ON TABLE notification IS
  'An in-app message for one recipient -- title, body, severity, read state and a link to the screen it refers to.';

COMMENT ON TABLE ai_agent IS
  'A configured AI assistant: provider, model, endpoint, encrypted key and its standing instructions. tool_uuid is an unguessable handle that exposes the agent as a callable tool.';

COMMENT ON TABLE database_connection_profile IS
  'An external database the query engine can read from -- host, port, database, type and encrypted credentials.';

COMMENT ON TABLE query_definition IS
  'A saved, versioned SQL query against one database_connection_profile.';

COMMENT ON TABLE query_schedule IS
  'A recurring run of a saved query: how often, when it next fires, and where the result is written. Polled without a user session, so it deliberately sees every tenant.';

COMMENT ON TABLE query_execution IS
  'One run of a saved query -- when it ran, its row count, and the bucket and key holding the output, or the error if it failed.';

COMMENT ON TABLE document_converter_task IS
  'A completed file conversion: the input and output formats, sizes, and the storage keys of both, so a converted file stays retrievable after the tab that made it is closed.';

COMMENT ON TABLE dynamic_form IS
  'A user-defined form. uuid is an unguessable handle used to share the form for filling without exposing its id.';

COMMENT ON TABLE dynamic_form_field IS
  'One field on a dynamic form -- type, label, ordering, width and its validation rules. Inherits its tenant through dynamic_form.';

COMMENT ON TABLE dynamic_form_submission IS
  'One filled-in response to a dynamic form, stored as a payload document.';

COMMENT ON TABLE pdf_highlighter_task IS
  'An uploaded PDF and its processing state, ready for regions to be marked on it.';

COMMENT ON TABLE pdf_highlighter_field IS
  'One marked region on a PDF -- its page, position and size, plus the text and XPath selectors used to find it again when the document reflows.';

COMMENT ON TABLE shedlock IS
  'Scheduler mutex. Stops two application instances from firing the same scheduled run; managed by ShedLock, not application code.';

COMMENT ON TABLE databasechangelog IS
  'Liquibase migration history. Managed by Liquibase -- do not edit by hand.';

COMMENT ON TABLE databasechangeloglock IS
  'Liquibase migration mutex. Managed by Liquibase -- do not edit by hand.';
