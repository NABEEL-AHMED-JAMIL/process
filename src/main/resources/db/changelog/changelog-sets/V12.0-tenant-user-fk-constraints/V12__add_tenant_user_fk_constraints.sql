-- Adds real DB-level foreign key constraints on every tenant_id/assigned_user_id/created_by/
-- updated_by column that only ever had an application-enforced relationship to
-- tenant(tenant_id) / app_user(app_user_id) -- these columns were plain Longs with no FK,
-- so a bad write (or a bug) could silently leave a dangling reference. Confirmed zero orphaned
-- rows across every one of these columns before writing this migration (see the corresponding
-- pojo changes adding the matching @ManyToOne relations in this same change).
--
-- No ON DELETE clause -- defaults to Postgres's NO ACTION/RESTRICT, which is intentional: this
-- app soft-deletes tenants/users (Status.Delete), it never issues a real DELETE FROM tenant/
-- app_user, so RESTRICT costs nothing today and is the safer default if that ever changes.
-- All target columns are nullable, so existing NULL values are unaffected either way.

ALTER TABLE ai_agent ADD CONSTRAINT fk_ai_agent_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE database_connection_profile ADD CONSTRAINT fk_database_connection_profile_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE database_connection_profile ADD CONSTRAINT fk_database_connection_profile_created_by FOREIGN KEY (created_by) REFERENCES app_user(app_user_id);
ALTER TABLE database_connection_profile ADD CONSTRAINT fk_database_connection_profile_updated_by FOREIGN KEY (updated_by) REFERENCES app_user(app_user_id);
ALTER TABLE document_converter_task ADD CONSTRAINT fk_document_converter_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE dynamic_form ADD CONSTRAINT fk_dynamic_form_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE kafka_connection_profile ADD CONSTRAINT fk_kafka_connection_profile_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE lookup_data ADD CONSTRAINT fk_lookup_data_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE pdf_highlighter_task ADD CONSTRAINT fk_pdf_highlighter_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE query_definition ADD CONSTRAINT fk_query_definition_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE query_definition ADD CONSTRAINT fk_query_definition_created_by FOREIGN KEY (created_by) REFERENCES app_user(app_user_id);
ALTER TABLE query_definition ADD CONSTRAINT fk_query_definition_updated_by FOREIGN KEY (updated_by) REFERENCES app_user(app_user_id);
ALTER TABLE query_execution ADD CONSTRAINT fk_query_execution_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE query_execution ADD CONSTRAINT fk_query_execution_created_by FOREIGN KEY (created_by) REFERENCES app_user(app_user_id);
ALTER TABLE query_schedule ADD CONSTRAINT fk_query_schedule_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE query_schedule ADD CONSTRAINT fk_query_schedule_created_by FOREIGN KEY (created_by) REFERENCES app_user(app_user_id);
ALTER TABLE query_schedule ADD CONSTRAINT fk_query_schedule_updated_by FOREIGN KEY (updated_by) REFERENCES app_user(app_user_id);
ALTER TABLE source_job ADD CONSTRAINT fk_source_job_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE source_job ADD CONSTRAINT fk_source_job_assigned_user FOREIGN KEY (assigned_user_id) REFERENCES app_user(app_user_id);
ALTER TABLE source_task ADD CONSTRAINT fk_source_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE source_task_type ADD CONSTRAINT fk_source_task_type_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE tenant_task_type_kafka_route ADD CONSTRAINT fk_tenant_task_type_kafka_route_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
ALTER TABLE app_user ADD CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);
