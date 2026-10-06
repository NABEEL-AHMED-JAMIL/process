-- source_job.job_status was found with 44 of 45 rows stored as all-caps ('ACTIVE') against the
-- app's Status enum, which is title-case (Active/Inactive/Delete) and stored correctly
-- everywhere else (source_task.task_status, tenant.status, app_user.status all already used the
-- right casing -- this was isolated to source_job). Hibernate's @Enumerated(EnumType.STRING)
-- does an exact, case-sensitive match, so any row with the wrong case was two different real
-- bugs at once: silently excluded from every typed JPQL query (e.g. listSourceJob's
-- "WHERE job_status IN ('Active','Inactive')" -- Postgres text comparison is case-sensitive, so
-- 'ACTIVE' never matched 'Active'), and a hard crash (IllegalArgumentException: No enum constant)
-- for any code path that tries to fully hydrate the row as an entity, e.g. a plain findAll().
UPDATE source_job SET job_status = 'Active' WHERE job_status = 'ACTIVE';
UPDATE source_job SET job_status = 'Inactive' WHERE job_status = 'INACTIVE';
UPDATE source_job SET job_status = 'Delete' WHERE job_status = 'DELETE';
