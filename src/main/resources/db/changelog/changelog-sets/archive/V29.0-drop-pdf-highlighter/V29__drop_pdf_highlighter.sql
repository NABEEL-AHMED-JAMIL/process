-- PDF Highlighter was removed whole on 2026-09-07 -- the entity/repository/service/controller
-- in process, the legacy scheduler1/src screen and service (its only UI; never migrated to
-- scheduler1/next), and now the two tables here, at the product owner's explicit call. Both
-- tables were confirmed empty in every environment reachable at the time
-- (pdf_highlighter_task=0, pdf_highlighter_field=0). The project's own synthesis document had
-- recommended migrating rather than dropping this feature, citing job-search's
-- pdf_highlighter_f768925.py/pdf_highligter_form_fill_F768924.py ETL pair as a live consumer of
-- the authoring UI this fed -- that pipeline pair itself is untouched by this migration (it has
-- no table of its own here, and its own dispatch mechanism, a lookup_data row, was already left
-- alone by V28's PIPELINE_IDS cleanup). The decision to drop anyway, despite that flagged
-- dependency, was made explicitly and knowingly, not overlooked.
--
-- Children before parents: pdf_highlighter_field holds the FK onto pdf_highlighter_task.
-- IF EXISTS throughout because neither table was ever created by Liquibase in the first place --
-- both were Hibernate ddl-auto=update artifacts (see V12/V14's own FK-constraint changesets,
-- which added constraints onto tables Liquibase itself never created), so an environment that
-- reached this point some other way (a fresh database, or one where a prior manual cleanup
-- already ran) must not fail this changeset.
DROP TABLE IF EXISTS pdf_highlighter_field;
DROP TABLE IF EXISTS pdf_highlighter_task;

DROP SEQUENCE IF EXISTS pdf_highlighter_field_id_seq;
DROP SEQUENCE IF EXISTS pdf_highlighter_task_id_seq;
