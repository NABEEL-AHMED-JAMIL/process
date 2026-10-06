-- The dynamic-forms feature was removed whole on 2026-09-03 -- screens, routes, controller,
-- service, entities -- at the product owner's explicit call. All three tables were confirmed
-- empty in every environment reachable at the time (dynamic_form=0, dynamic_form_field=0,
-- dynamic_form_submission=0), and nothing outside this trio ever referenced them by foreign key.
--
-- Children before parent: dynamic_form_field and dynamic_form_submission both hold a FK onto
-- dynamic_form, so dropping the parent first would fail. IF EXISTS throughout because none of
-- these tables were ever created by Liquibase in the first place -- they were Hibernate
-- ddl-auto=update artifacts, so an environment that reached this point some other way (a fresh
-- database, or one where a prior manual cleanup already ran) must not fail this changeset.
DROP TABLE IF EXISTS dynamic_form_field;
DROP TABLE IF EXISTS dynamic_form_submission;
DROP TABLE IF EXISTS dynamic_form;

DROP SEQUENCE IF EXISTS dynamic_form_field_seq;
DROP SEQUENCE IF EXISTS dynamic_form_submission_seq;
DROP SEQUENCE IF EXISTS dynamic_form_seq;
