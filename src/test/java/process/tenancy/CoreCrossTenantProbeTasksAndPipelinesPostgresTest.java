package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.SourceTaskDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static process.tenancy.CoreProbeFixture.A_PIPELINE_ID;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.B_TASK;
import static process.tenancy.CoreProbeFixture.A_TASK;
import static process.tenancy.CoreProbeFixture.A_TYPE;
import static process.tenancy.CoreProbeFixture.B_TYPE;
import static process.tenancy.CoreProbeFixture.B_HOME;
import static process.tenancy.CoreProbeFixture.B_GROUP;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_PIPELINE;
import static process.tenancy.CoreProbeFixture.B_PIPELINE_ID;
import static process.tenancy.CoreProbeFixture.taskSheet;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.GONE;
import static process.tenancy.CoreProbeFixture.UNKNOWN;
import static process.tenancy.CoreProbeFixture.PLATFORM;
import static process.tenancy.CoreProbeFixture.C;
import static process.tenancy.CoreProbeFixture.DEFAULT_WS;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.tenantlessAdmin;
import static process.tenancy.CoreProbeFixture.PLATFORM_PIPELINE_ID;
import static process.tenancy.CoreProbeFixture.PLATFORM_PIPELINE;
import static process.tenancy.CoreProbeFixture.DEFAULT_TYPE;
import process.tenancy.CoreProbeFixture.Caller;

/**
 * MIG-166's CI cross-tenant probe for Core, the definitions jobs are made from: tasks (/sourceTask.json) and the
 * pipelines their payloads follow (/pipeline.json) -- called by workspace A's tenant admin, and A's tenant user where
 * the endpoint is lowered to TENANT_USER, with B's task, topic, home page, group, configuration key and pipeline,
 * against a real etl_job (CoreProbeFixture).
 *
 * A task links a topic (and through it a Kafka connection), a home page resolved to a URL at dispatch, a group, and
 * ${config:...} / ${secret:...} references resolved at run time. Every one of those links is a way to reach B's rows
 * from a row of A's, so each is aimed at B. A task or a pipeline A creates must land in A whatever tenantId its body
 * names.
 *
 * Two more questions, pinned end to end over the real tenant table rather than a mock: a platform administrator
 * must name a live workspace for a new task (a deleted one or an id with no row writes nothing -- with MIG-166 no
 * foreign key would stop it); and a caller with no workspace sees none of anyone's tasks or pipelines, the
 * platform's legacy tenantless pipeline included, and can create nothing anywhere -- not even in the workspace coded
 * "default". Opt-in, like every ScratchPostgres test.
 */
class CoreCrossTenantProbeTasksAndPipelinesPostgresTest {

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_tasks");
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() {
        fx.reset();
    }

    private static SourceTaskDto task(Long taskId, String name, long typeId) {
        SourceTaskDto dto = new SourceTaskDto();
        dto.setTaskDetailId(taskId);
        dto.setTaskName(name);
        dto.setTaskPayload("<task><bucket>acme-files</bucket></task>");
        dto.setPipelineId(A_PIPELINE_ID);
        SourceTaskTypeDto type = new SourceTaskTypeDto();
        type.setSourceTaskTypeId(typeId);
        dto.setSourceTaskType(type);
        return dto;
    }

    private static Pipeline form(Long pipelineKey, String pipelineId, long topic) {
        Pipeline form = new Pipeline();
        form.setPipelineKey(pipelineKey);
        form.setPipelineId(pipelineId);
        form.setPipelineName("Acme Form " + pipelineId);
        form.setSourceTaskTypeId(topic);
        PipelineField field = new PipelineField();
        field.setTagKey("acme_field");
        field.setLabel("Acme field");
        field.setFieldType("text");
        form.getFields().add(field);
        return form;
    }

    @Test
    void noTaskOrPipelineEndpointHandsTheCallerAnotherWorkspacesDefinitionsOrChangesThem() throws Exception {
        String before = fx.foreignRows();
        long tasksBefore = fx.count("SELECT count(*) FROM source_task");
        long pipelinesBefore = fx.count("SELECT count(*) FROM pipeline");

        // ---- /sourceTask.json: the three reads a tenant user reaches, as the user and as the admin
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            fx.probe("POST sourceTask.json/listSourceTask", caller,
                () -> fx.sourceTasks.listSourceTask(1L, 50L, null, null, null, null, null));
            assertThat(fx.probe("GET sourceTask.json/fetchSourceTaskWithSourceTaskId", caller,
                () -> fx.sourceTasks.fetchSourceTaskWithSourceTaskId(B_TASK))).contains(REFUSED);
            fx.probe("POST sourceTask.json/fetchAllLinkJobsWithSourceTaskId", caller,
                () -> fx.sourceTasks.fetchAllLinkJobsWithSourceTaskId(B_TASK, 1L, 50L, null, null, null, null, null));
            // A's own task, whose jobs include the colleague's: a tenant user is shown their own.
            fx.probe("POST sourceTask.json/fetchAllLinkJobsWithSourceTaskId(my task)", caller,
                () -> fx.sourceTasks.fetchAllLinkJobsWithSourceTaskId(A_TASK, 1L, 50L, null, null, null, null, null));
        }
        // ---- /sourceTask.json: the admin's
        fx.probe("GET sourceTask.json/downloadListSourceTask", ADMIN_OF_A, fx.sourceTasks::downloadListSourceTask);
        fx.probe("GET sourceTask.json/downloadSourceTaskTemplate", ADMIN_OF_A, fx.sourceTasks::downloadSourceTaskTemplate);
        assertThat(fx.probe("PUT sourceTask.json/updateSourceTask", ADMIN_OF_A,
            () -> fx.sourceTasks.updateSourceTask(task(B_TASK, "Renamed By Acme", A_TYPE)))).contains(REFUSED);
        assertThat(fx.probe("PUT sourceTask.json/updateSourceTask(my task onto their topic)", ADMIN_OF_A,
            () -> fx.sourceTasks.updateSourceTask(task(A_TASK, "Acme Task", B_TYPE)))).contains(REFUSED);
        SourceTaskDto theirHomePage = task(A_TASK, "Acme Task", A_TYPE);
        theirHomePage.setHomePageId(String.valueOf(B_HOME));
        assertThat(fx.probe("PUT sourceTask.json/updateSourceTask(my task, their home page)", ADMIN_OF_A,
            () -> fx.sourceTasks.updateSourceTask(theirHomePage))).contains(REFUSED);
        assertThat(fx.probe("PUT sourceTask.json/deleteSourceTask", ADMIN_OF_A,
            () -> fx.sourceTasks.deleteSourceTask(task(B_TASK, null, A_TYPE)))).contains(REFUSED);

        assertThat(fx.probe("POST sourceTask.json/addSourceTask(their topic)", ADMIN_OF_A,
            () -> fx.sourceTasks.addSourceTask(task(null, "acme task on their topic", B_TYPE)))).contains(REFUSED);
        SourceTaskDto withTheirHome = task(null, "acme task with their home page", A_TYPE);
        withTheirHome.setHomePageId(String.valueOf(B_HOME));
        assertThat(fx.probe("POST sourceTask.json/addSourceTask(their home page)", ADMIN_OF_A,
            () -> fx.sourceTasks.addSourceTask(withTheirHome))).contains(REFUSED);
        SourceTaskDto withTheirGroup = task(null, "acme task in their group", A_TYPE);
        withTheirGroup.setGroupId(String.valueOf(B_GROUP));
        assertThat(fx.probe("POST sourceTask.json/addSourceTask(their group)", ADMIN_OF_A,
            () -> fx.sourceTasks.addSourceTask(withTheirGroup))).contains(REFUSED);
        for (String reference : new String[] {"${config:BRAVO_KEY}", "${secret:BRAVO_SECRET}"}) {
            SourceTaskDto usingTheirConfiguration = task(null, "acme task using " + reference, A_TYPE);
            usingTheirConfiguration.setTaskPayload("<task><bucket>acme-files</bucket><token>" + reference + "</token></task>");
            assertThat(fx.probe("POST sourceTask.json/addSourceTask(their configuration)", ADMIN_OF_A,
                () -> fx.sourceTasks.addSourceTask(usingTheirConfiguration))).contains(REFUSED);
        }
        assertThat(fx.probe("POST sourceTask.json/uploadSourceTask(their topic)", ADMIN_OF_A,
            () -> fx.sourceTasks.uploadSourceTask(taskSheet(null, B_TYPE, "acme upload on their topic", null)))).contains(REFUSED);
        assertThat(fx.probe("POST sourceTask.json/uploadSourceTask(their home page)", ADMIN_OF_A,
            () -> fx.sourceTasks.uploadSourceTask(taskSheet(B, A_TYPE, "acme upload with their home", String.valueOf(B_HOME)))))
            .contains(REFUSED);

        // ---- /pipeline.json: the reads a tenant user reaches, as the user and as the admin
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            fx.probe("GET pipeline.json/listPipelines", caller, () -> fx.pipelines.listPipelines(1L, 50L, null, null, null));
            assertThat(fx.probe("GET pipeline.json/fields", caller, () -> fx.pipelines.fields(B_PIPELINE))).contains(REFUSED);
            fx.probe("GET pipeline.json/listForTopic", caller, () -> fx.pipelines.listForTopic(B_TYPE));
            // One method answers both paths; each path is a URL a tenant can call, so each is probed by name.
            fx.probe("GET pipeline.json/definition", caller, () -> fx.pipelines.formForPipeline(B_PIPELINE_ID, B));
            fx.probe("GET pipeline.json/formForPipeline", caller, () -> fx.pipelines.formForPipeline(B_PIPELINE_ID, B));
        }
        // ---- /pipeline.json: the admin's, each alias by name
        fx.probe("GET pipeline.json/list", ADMIN_OF_A, () -> fx.pipelines.listForms(1L, 50L, null, null, null, B, false));
        fx.probe("GET pipeline.json/listForms", ADMIN_OF_A, () -> fx.pipelines.listForms(1L, 50L, null, null, null, B, false));
        assertThat(fx.probe("POST pipeline.json/save", ADMIN_OF_A,
            () -> fx.pipelines.saveForm(form(B_PIPELINE, B_PIPELINE_ID, A_TYPE)))).contains(REFUSED);
        assertThat(fx.probe("POST pipeline.json/saveForm", ADMIN_OF_A,
            () -> fx.pipelines.saveForm(form(B_PIPELINE, B_PIPELINE_ID, B_TYPE)))).contains(REFUSED);
        assertThat(fx.probe("POST pipeline.json/saveForm(new, on their topic)", ADMIN_OF_A,
            () -> fx.pipelines.saveForm(form(null, "ACME-ON-THEIR-TOPIC", B_TYPE)))).contains(REFUSED);
        assertThat(fx.probe("DELETE pipeline.json/delete", ADMIN_OF_A, () -> fx.pipelines.deleteForm(B_PIPELINE))).contains(REFUSED);
        assertThat(fx.probe("DELETE pipeline.json/deleteForm", ADMIN_OF_A, () -> fx.pipelines.deleteForm(B_PIPELINE)))
            .contains(REFUSED);

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's, the platform's or the colleague's changed").isEqualTo(before);
        assertThat(fx.count("SELECT count(*) FROM source_task")).as("a refused create writes no task").isEqualTo(tasksBefore);
        assertThat(fx.count("SELECT count(*) FROM pipeline")).as("a refused save writes no pipeline").isEqualTo(pipelinesBefore);
        verify(fx.notifications, never()).notificationCreated(eq(B), any());
    }

    /** What an A admin creates lands in A, whatever tenantId the body or the upload names. */
    @Test
    void definitionsTheCallerCreatesLandInTheirWorkspaceWhateverTheBodyNames() throws Exception {
        SourceTaskDto intoB = task(null, "acme task naming b", A_TYPE);
        intoB.setTenantId(B);
        assertThat(fx.probe("POST sourceTask.json/addSourceTask(body names B)", ADMIN_OF_A,
            () -> fx.sourceTasks.addSourceTask(intoB))).contains(SUCCEEDED);
        assertThat(fx.probe("POST sourceTask.json/uploadSourceTask(body names B)", ADMIN_OF_A,
            () -> fx.sourceTasks.uploadSourceTask(taskSheet(B, A_TYPE, "acme upload naming b", null)))).contains(SUCCEEDED);
        Pipeline formIntoB = form(null, "ACME-NAMING-B", A_TYPE);
        formIntoB.setTenantId(B);
        assertThat(fx.probe("POST pipeline.json/saveForm(body names B)", ADMIN_OF_A, () -> fx.pipelines.saveForm(formIntoB)))
            .contains(SUCCEEDED);

        assertThat(fx.tenantOf("SELECT tenant_id FROM source_task WHERE task_name = ?", "acme task naming b")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT p.tenant_id FROM source_task_payload p JOIN source_task t ON t.task_detail_id = p.payload_id "
            + "WHERE t.task_name = ?", "acme task naming b")).as("and its tag rows with it").isEqualTo(A);
        assertThat(fx.tenantOf("SELECT tenant_id FROM source_task WHERE task_name = ?", "acme upload naming b")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT tenant_id FROM pipeline WHERE pipeline_id = ?", "ACME-NAMING-B")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT f.tenant_id FROM pipeline_field f JOIN pipeline p ON p.pipeline_key = f.pipeline_key "
            + "WHERE p.pipeline_id = ?", "ACME-NAMING-B")).isEqualTo(A);
        assertThat(fx.leaks).isEmpty();
        verify(fx.notifications, never()).notificationCreated(eq(B), any());
    }

    /**
     * A platform administrator has no workspace of their own and must name one: a deleted one (tenant status Delete)
     * or an id with no row is refused, and nothing is written under it. Pinned over the real tenant table.
     */
    @Test
    void aPlatformAdministratorCannotFileATaskUnderAWorkspaceThatIsGoneOrNeverWas() throws Exception {
        assertThat(fx.rowsNaming(GONE, UNKNOWN)).isZero();
        for (long notLive : new long[] {GONE, UNKNOWN}) {
            SourceTaskDto task = task(null, "platform task for " + notLive, A_TYPE);
            task.setTenantId(notLive);
            assertThat(fx.probe("POST sourceTask.json/addSourceTask(workspace not live)", PLATFORM,
                () -> fx.sourceTasks.addSourceTask(task))).contains(REFUSED);
            assertThat(fx.probe("POST sourceTask.json/uploadSourceTask(workspace not live)", PLATFORM,
                () -> fx.sourceTasks.uploadSourceTask(taskSheet(notLive, A_TYPE, "platform upload for " + notLive, null))))
                .contains(REFUSED);
        }
        assertThat(fx.rowsNaming(GONE, UNKNOWN)).as("nothing filed under a workspace that is not live").isZero();
    }

    /**
     * The null-equals-null case: a token with a role and no workspace -- none named, or 0 or -1 -- owns nothing. It
     * lists no task or pipeline of anyone's -- not the platform's legacy tenantless pipeline either, which "tenant_id = null" must not match -- and
     * creates nothing: not a task, and not a pipeline filed under the workspace coded "default", which is someone's.
     */
    @Test
    void aCallerWithNoWorkspaceGetsNothingOfAnyonesAndCreatesNothing() throws Exception {
        String before = fx.foreignRows(A, B, C, DEFAULT_WS, 0L, -1L);
        long tasksBefore = fx.count("SELECT count(*) FROM source_task");
        long pipelinesBefore = fx.count("SELECT count(*) FROM pipeline");

        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessAdmin(none);
            fx.probe("POST sourceTask.json/listSourceTask", caller,
                () -> fx.sourceTasks.listSourceTask(1L, 50L, null, null, null, null, null));
            fx.probe("GET sourceTask.json/downloadListSourceTask", caller, fx.sourceTasks::downloadListSourceTask);
            assertThat(fx.probe("GET sourceTask.json/fetchSourceTaskWithSourceTaskId", caller,
                () -> fx.sourceTasks.fetchSourceTaskWithSourceTaskId(A_TASK))).contains(REFUSED);
            fx.probe("GET pipeline.json/listForms", caller, () -> fx.pipelines.listForms(1L, 50L, null, null, null, null, false));
            fx.probe("GET pipeline.json/listPipelines", caller, () -> fx.pipelines.listPipelines(1L, 50L, null, null, null));
            fx.probe("GET pipeline.json/formForPipeline(the platform's tenantless pipeline)", caller,
                () -> fx.pipelines.formForPipeline(PLATFORM_PIPELINE_ID, null));
            assertThat(fx.probe("GET pipeline.json/fields(the platform's tenantless pipeline)", caller,
                () -> fx.pipelines.fields(PLATFORM_PIPELINE))).contains(REFUSED);

            assertThat(fx.probe("POST sourceTask.json/addSourceTask", caller,
                () -> fx.sourceTasks.addSourceTask(task(null, "orphan task " + none, A_TYPE)))).contains(REFUSED);
            assertThat(fx.probe("POST pipeline.json/saveForm(a topic of the default workspace)", caller,
                () -> fx.pipelines.saveForm(form(null, "ORPHAN-PIPE" + none, DEFAULT_TYPE)))).contains(REFUSED);
        }

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.count("SELECT count(*) FROM source_task")).isEqualTo(tasksBefore);
        assertThat(fx.count("SELECT count(*) FROM pipeline")).as("no pipeline filed anywhere, the default workspace included")
            .isEqualTo(pipelinesBefore);
        assertThat(fx.rowsNaming(0L, -1L)).as("nothing filed under a tenant id that names no workspace").isZero();
        assertThat(fx.foreignRows(A, B, C, DEFAULT_WS, 0L, -1L)).isEqualTo(before);
    }
}
