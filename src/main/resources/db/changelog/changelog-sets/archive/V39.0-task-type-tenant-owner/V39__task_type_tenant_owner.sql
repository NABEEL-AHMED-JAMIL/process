-- Every task type belongs to exactly one workspace.
--
-- source_task_type.tenant_id was nullable, and a NULL meant "the platform's, shared with every
-- workspace" -- the visibility query said so explicitly:
--
--     where source_task_type.tenant_id = :tenantId or source_task_type.tenant_id is null
--
-- The trouble is that NULL was also what you got by ACCIDENT. SettingServiceImpl.getSourceTaskType
-- set the owner to `TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId()`, so
-- every task type a platform admin created for one workspace was silently shared with all of
-- them. Five had been: "Test User 1 Task" through "Test User 5 Task", carrying the topics
-- test-user-1-topic .. test-user-5-topic, appeared on every tenant's Task Types screen. One
-- workspace could read another's Kafka topic names off a settings page.
--
-- So the meaning of NULL is being removed rather than the accident being patched: after this,
-- tenant_id is NOT NULL, a task type is visible to exactly one workspace, and a platform admin
-- creating one has to say which. There is no longer a value that means "everyone".
--
-- What happens to the seven rows that are NULL today, decided from what actually uses them:
--
--   1019  ETL Scrapping Pipeline  -- 2 tasks, ALL of them in "Default" (tenant 1815). Not shared
--                                    in practice at all, just unowned. Given to its only user.
--   1020  TPD Test Loop           -- 0 tasks
--   1021-1025  Test User 1-5 Task -- 0 tasks each
--
-- The six unused ones are deactivated rather than deleted. source_task has a foreign key to this
-- table and job history points through it; deleting a row that some future audit query joins to
-- would turn a historical run into a broken join, and "Inactive" already means "not offered when
-- building a task" everywhere in this codebase. If they are genuinely finished with, deleting
-- them is a separate decision made with that history in view.

-- Assign the one in-use shared type to the workspace that is actually using it. Written as a
-- lookup rather than a literal so it cannot bind the wrong workspace on another database, and
-- guarded so it only moves a row that is still unowned.
UPDATE source_task_type stt
   SET tenant_id = (
        SELECT st.tenant_id FROM source_task st
         WHERE st.source_task_type_id = stt.source_task_type_id
           AND st.tenant_id IS NOT NULL
         GROUP BY st.tenant_id
         ORDER BY count(*) DESC
         LIMIT 1)
 WHERE stt.tenant_id IS NULL
   AND EXISTS (SELECT 1 FROM source_task st
                WHERE st.source_task_type_id = stt.source_task_type_id
                  AND st.tenant_id IS NOT NULL);

-- Anything still unowned is used by nothing. Park it against the default workspace and switch it
-- off, so it stops appearing on every other workspace's screen without vanishing from under any
-- history that references it.
UPDATE source_task_type
   SET task_type_status = 'Inactive',
       tenant_id = (SELECT tenant_id FROM tenant
                     WHERE lower(tenant_code) = 'default' OR lower(tenant_name) = 'default'
                     ORDER BY tenant_id LIMIT 1)
 WHERE tenant_id IS NULL;

-- <b>Refuses to apply if anything is still unowned</b>, which is the point: the constraint below
-- is what stops the accident recurring, and applying it over a row this migration could not place
-- would either fail confusingly or silently hide a task type somebody is using. A failure here
-- means a task type exists that no workspace owns and none of its tasks say who should -- look at
-- it and decide, rather than letting a default pick for somebody.
ALTER TABLE source_task_type ALTER COLUMN tenant_id SET NOT NULL;

COMMENT ON COLUMN source_task_type.tenant_id IS
    'The one workspace this task type belongs to. Not nullable: a NULL used to mean "shared with every workspace", which is how one workspace came to see another''s Kafka topics on the Task Types screen.';
