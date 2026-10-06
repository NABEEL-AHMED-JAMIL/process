-- PIPELINE_IDS is retired as a lookup family. A pipeline is now defined by creating its Task
-- Form (Configuration -> Pipeline Forms), which already keys itself on the pipeline's own id
-- string (task_form.pipeline_id) -- so a second, parallel catalogue of the same ids as lookup
-- rows was redundant, and it stored the wrong thing besides: the *lookup row id* rather than
-- the pipeline's real code, resolved back to the code only at dispatch time
-- (ProducerBulkEngine.getSourceJobDetail). Source Task's Pipeline picker now lists pipelines by
-- reading Task Forms directly (GET taskForm.json/listForms) and stores the real pipeline id, so
-- that resolution step is gone too.
--
-- Same precedent as V25-V27 (drop-avatar-backup, drop-dynamic-forms, drop-query-engine): rows
-- are removed, not archived, because the replacement (Task Forms) already covers everything a
-- tenant could have used PIPELINE_IDS for, and TENANT_OWNED_LOOKUPS's own long-standing comment
-- already documents that a task keeps working when the lookup row behind its stored id
-- disappears -- it just stops resolving to a friendly name. That was already true before this
-- migration (see V10's replacement of pipeline child 1016); this migration is the same kind of
-- change, not a new risk.
--
-- Real, still-live pipeline codes to note for whoever re-creates them as Task Forms next:
-- F768924 (PDF Highlighter Form Fill), F768925 (PDF Highlighter Text Extraction),
-- F768926 (Hurricanes ETL), F768927 (MP3 Noise Processing) -- the same four
-- tpd_scrapping_listener.py already routes on. Deleting the lookup rows does not touch any
-- existing SourceTask's stored pipeline_id value or any pending job's payload; it only removes
-- where the *admin UI* used to look up the list of choices.
--
-- By parent_lookup_id rather than a hardcoded child list: environments used since V10 have
-- accumulated far more than the original four children under 1015 (test pipelines added through
-- the app itself), and a hardcoded DELETE ... WHERE lookup_id IN (...) left the rest behind,
-- which then blocked deleting the parent on lookup_data's own FK back onto itself.
DELETE FROM lookup_data WHERE parent_lookup_id = 1015;
DELETE FROM lookup_data WHERE lookup_id = 1015;

-- The source_task_type description written in V10 pointed at "the PIPELINE_IDS lookup" as where
-- to find what each code means -- that sentence is now stale, since the four codes it lists are
-- still the right ones but the lookup it names no longer exists.
UPDATE source_task_type SET description =
  '[ETL scrapping pipeline -- routes by pipelineId (defined under Configuration -> Pipeline Forms): F768924 PDF Highlighter Form Fill, F768925 PDF Highlighter Text Extraction, F768926 Hurricanes ETL, F768927 MP3 Noise Processing]'
  WHERE source_task_type_id = 1011;
