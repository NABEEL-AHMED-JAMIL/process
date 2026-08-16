-- Closes out the remaining loose FK-like columns found across the pojo layer after V12/V13:
-- dynamic_form_submission.dynamic_form_id, pdf_highlighter_field.pdf_highlighter_task_id,
-- the Query Engine's cross-references (query_definition/query_execution/query_schedule <->
-- database_connection_profile, and query_execution/query_schedule -> query_definition,
-- query_execution -> query_schedule), and source_task_type/tenant_task_type_kafka_route's
-- kafka_connection_profile_id (+ tenant_task_type_kafka_route.source_task_type_id).
-- Zero orphaned rows confirmed across all of these before writing this migration. No ON
-- DELETE clause (defaults to NO ACTION), consistent with V12/V13 -- none of these parent
-- rows are ever hard-deleted by this app.

ALTER TABLE dynamic_form_submission ADD CONSTRAINT fk_dynamic_form_submission_dynamic_form FOREIGN KEY (dynamic_form_id) REFERENCES dynamic_form(dynamic_form_id);
ALTER TABLE pdf_highlighter_field ADD CONSTRAINT fk_pdf_highlighter_field_task FOREIGN KEY (pdf_highlighter_task_id) REFERENCES pdf_highlighter_task(pdf_highlighter_task_id);
ALTER TABLE query_definition ADD CONSTRAINT fk_query_definition_db_profile FOREIGN KEY (database_connection_profile_id) REFERENCES database_connection_profile(database_connection_profile_id);
ALTER TABLE query_execution ADD CONSTRAINT fk_query_execution_query_definition FOREIGN KEY (query_id) REFERENCES query_definition(query_id);
ALTER TABLE query_execution ADD CONSTRAINT fk_query_execution_db_profile FOREIGN KEY (database_connection_profile_id) REFERENCES database_connection_profile(database_connection_profile_id);
ALTER TABLE query_execution ADD CONSTRAINT fk_query_execution_query_schedule FOREIGN KEY (schedule_id) REFERENCES query_schedule(schedule_id);
ALTER TABLE query_schedule ADD CONSTRAINT fk_query_schedule_query_definition FOREIGN KEY (query_id) REFERENCES query_definition(query_id);
ALTER TABLE query_schedule ADD CONSTRAINT fk_query_schedule_db_profile FOREIGN KEY (database_connection_profile_id) REFERENCES database_connection_profile(database_connection_profile_id);
ALTER TABLE source_task_type ADD CONSTRAINT fk_source_task_type_kafka_profile FOREIGN KEY (kafka_connection_profile_id) REFERENCES kafka_connection_profile(kafka_connection_profile_id);
ALTER TABLE tenant_task_type_kafka_route ADD CONSTRAINT fk_ttkr_source_task_type FOREIGN KEY (source_task_type_id) REFERENCES source_task_type(source_task_type_id);
ALTER TABLE tenant_task_type_kafka_route ADD CONSTRAINT fk_ttkr_kafka_profile FOREIGN KEY (kafka_connection_profile_id) REFERENCES kafka_connection_profile(kafka_connection_profile_id);
