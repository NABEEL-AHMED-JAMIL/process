-- The Query Engine and Search Engine features were removed whole on 2026-09-05 -- screens (in
-- both the "next" and legacy Angular apps), routes, controllers, services, entities -- at the
-- product owner's explicit call, including the database tables. All four tables were confirmed
-- empty in every environment reachable at the time (database_connection_profile=0,
-- query_definition=0, query_execution=0, query_schedule=0), and nothing outside this quartet
-- ever referenced them by foreign key (Search Engine itself never had a table of its own -- it
-- ran ad-hoc SQL through the shared QueryService and returned the result without persisting
-- anything).
--
-- Children before parents: query_execution holds FKs onto query_schedule, query_definition and
-- database_connection_profile; query_schedule and query_definition each hold a FK onto
-- database_connection_profile; query_schedule also holds one onto query_definition. IF EXISTS
-- throughout because none of these tables were ever created by Liquibase in the first place --
-- they were Hibernate ddl-auto=update artifacts (see V12/V14's own FK-constraint changesets,
-- which added constraints onto tables Liquibase itself never created), so an environment that
-- reached this point some other way (a fresh database, or one where a prior manual cleanup
-- already ran) must not fail this changeset.
DROP TABLE IF EXISTS query_execution;
DROP TABLE IF EXISTS query_schedule;
DROP TABLE IF EXISTS query_definition;
DROP TABLE IF EXISTS database_connection_profile;

DROP SEQUENCE IF EXISTS query_execution_seq;
DROP SEQUENCE IF EXISTS query_schedule_seq;
DROP SEQUENCE IF EXISTS query_definition_seq;
DROP SEQUENCE IF EXISTS database_connection_profile_seq;
