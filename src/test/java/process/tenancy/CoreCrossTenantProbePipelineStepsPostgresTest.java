package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.pipeline.PipelineDefinitionService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static process.tenancy.CoreProbeFixture.*;

/**
 * MIG-166's cross-tenant probe for pipelines as ordered steps (MIG-230): a pipeline's definition read and saved by
 * workspace A's tenant user and admin, aimed at workspace B's pipeline and at the platform's, and by callers with no
 * workspace. Against a real etl_job as process_app (CoreProbeFixture). B's pipeline has a saved definition whose sample
 * rows carry one of B's markers; a probe passes when nothing of B's comes back and no version is added to B's or the
 * platform's pipeline. Opt-in, like every ScratchPostgres test.
 */
class CoreCrossTenantProbePipelineStepsPostgresTest {

    private static final String B_DEFINITION = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"config\":"
        + "{\"rows\":[{\"note\":\"bravo payload marker\"}]}}]}";
    private static final String A_DRAFT = "version: 1\nsteps:\n  - key: read\n    task: sample\n    config:\n      rows:\n"
        + "        - {id: 1, name: Acme row}\n  - key: keep\n    task: select\n    config: {columns: [id]}\n";

    private static CoreProbeFixture fx;
    private static long bStep;
    private static long colleagueStep;
    private static long aStep;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_pipeline_steps");
        JdbcTemplate sql = fx.db.jdbc();
        long bDefinition = sql.queryForObject("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 1, ?::json) "
            + "RETURNING pipeline_definition_id", Long.class, B_PIPELINE, B_DEFINITION);
        // Steps of B's run, of A's colleague's run and of A's own run, each with a log line and a dataset.
        bStep = step(sql, B_RUN, bDefinition, "bravo run message marker");
        colleagueStep = step(sql, COLLEAGUE_RUN, null, "colleague run message");
        aStep = step(sql, A_RUN, null, "acme step line");
        // MIG-231: B has switched 'select' off in its workspace.
        sql.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled) VALUES (?, 'select', false)", B);
    }

    private static long step(JdbcTemplate sql, long run, Long definition, String line) {
        long id = sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status, status_message, "
            + "pipeline_definition_id, records_in, records_out, tries, on_error) VALUES (?, 0, 'sample', 'read', 'Completed', ?, ?, 0, 1, 1, "
            + "'fail') RETURNING step_execution_id", Long.class, run, line, definition);
        sql.update("INSERT INTO step_log (step_execution_id, line_no, message) VALUES (?, 1, ?)", id, line);
        sql.update("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns) VALUES (?, 'output', ?, 1, "
            + "'[\"note\"]'::jsonb)", id, "datasets/" + run + "/1/read/output.json");
        return id;
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

    private static PipelineDefinitionService.DefinitionRequest draft(long pipelineKey, String text) {
        PipelineDefinitionService.DefinitionRequest request = new PipelineDefinitionService.DefinitionRequest();
        request.setPipelineKey(pipelineKey);
        request.setText(text);
        return request;
    }

    private static long versions(long pipelineKey) {
        return fx.db.jdbc().queryForObject("SELECT count(*) FROM pipeline_definition WHERE pipeline_key = ?", Long.class, pipelineKey);
    }

    @Test
    void noDefinitionEndpointReadsOrChangesAnotherWorkspacesOrThePlatformsPipeline() {
        String before = fx.foreignRows();
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            for (long theirs : new long[] {B_PIPELINE, PLATFORM_PIPELINE}) {
                assertThat(fx.probe("GET pipeline.json/steps/definition", caller, () -> fx.pipelineSteps.definition(theirs)))
                    .contains(REFUSED).contains("That pipeline no longer exists.").doesNotContain("bravo payload marker");
                assertThat(fx.probe("POST pipeline.json/steps/save", caller, () -> fx.pipelineSteps.save(draft(theirs, A_DRAFT))))
                    .contains(REFUSED);
            }
        }
        assertThat(versions(B_PIPELINE)).isEqualTo(1);
        assertThat(versions(PLATFORM_PIPELINE)).isZero();
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's or the platform's changed").isEqualTo(before);
    }

    @Test
    void aCallerWithNoWorkspaceGetsNothingAndSavesNothing() {
        String before = fx.foreignRows(A, B, 0L, -1L);
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            for (long pipeline : new long[] {A_PIPELINE, B_PIPELINE, PLATFORM_PIPELINE}) {
                assertThat(fx.probe("GET pipeline.json/steps/definition", caller, () -> fx.pipelineSteps.definition(pipeline)))
                    .contains(REFUSED);
                assertThat(fx.probe("POST pipeline.json/steps/save", caller, () -> fx.pipelineSteps.save(draft(pipeline, A_DRAFT))))
                    .contains(REFUSED);
            }
        }
        assertThat(fx.foreignRows(A, B, 0L, -1L)).isEqualTo(before);
        assertThat(fx.leaks).isEmpty();
    }

    /** The control: A's own pipeline reads as its legacy step, saves A's draft as version 1, and reads it back. */
    @Test
    void myOwnPipelineReadsAsItsLegacyStepThenSavesAndReadsMyDefinition() {
        long mine = A_PIPELINE;
        long already = versions(mine);
        if (already == 0) {
            assertThat(fx.probe("GET pipeline.json/steps/definition(mine)", USER_OF_A, () -> fx.pipelineSteps.definition(mine)))
                .contains(SUCCEEDED).contains("\"legacy\":true").contains("\"task\":\"legacy\"").contains(A_PIPELINE_ID);
        }
        // Saving is for tenant admins: @PreAuthorize on the controller, as the pipeline's form. The service's own rule is the
        // workspace.
        assertThat(fx.probe("POST pipeline.json/steps/save(mine)", ADMIN_OF_A, () -> fx.pipelineSteps.save(draft(mine, A_DRAFT))))
            .contains(SUCCEEDED).contains("Saved as version");
        assertThat(fx.probe("POST pipeline.json/steps/save(mine again, unchanged)", ADMIN_OF_A,
            () -> fx.pipelineSteps.save(draft(mine, A_DRAFT)))).contains(SUCCEEDED).contains("Unchanged");
        assertThat(fx.probe("GET pipeline.json/steps/definition(mine)", ADMIN_OF_A, () -> fx.pipelineSteps.definition(mine)))
            .contains(SUCCEEDED).contains("\"legacy\":false").contains("Acme row").contains("\"stored\":true");
        assertThat(fx.db.jdbc().queryForObject("SELECT tenant_id FROM pipeline_definition WHERE pipeline_key = ? ORDER BY version DESC "
            + "LIMIT 1", Long.class, mine)).isEqualTo(A);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void noStepReadAnswersForAnotherWorkspacesOrAColleaguesRun() {
        String before = fx.foreignRows();
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] runs = caller == USER_OF_A ? new long[] {B_RUN, COLLEAGUE_RUN} : new long[] {B_RUN};
            for (long theirs : runs) {
                assertThat(fx.probe("GET sourceJob.json/stepExecutions", caller, () -> fx.stepTimeline.stepExecutions(theirs, null)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
            }
            long[] steps = caller == USER_OF_A ? new long[] {bStep, colleagueStep} : new long[] {bStep};
            for (long theirs : steps) {
                assertThat(fx.probe("GET sourceJob.json/stepLogs", caller, () -> fx.stepTimeline.stepLogs(theirs)))
                    .contains(REFUSED).contains("Step not found with stepExecutionId.");
            }
        }
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            assertThat(fx.probe("GET sourceJob.json/stepExecutions", caller, () -> fx.stepTimeline.stepExecutions(A_RUN, null)))
                .contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/stepLogs", caller, () -> fx.stepTimeline.stepLogs(aStep))).contains(REFUSED);
        }
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).isEqualTo(before);
    }

    private static PipelineDefinitionService.TaskSwitchRequest switchOf(String code, Boolean enabled) {
        PipelineDefinitionService.TaskSwitchRequest request = new PipelineDefinitionService.TaskSwitchRequest();
        request.setCode(code);
        request.setEnabled(enabled);
        return request;
    }

    private static List<String> switches(long workspace) {
        return fx.db.jdbc().queryForList("SELECT task_code || '=' || enabled FROM task_registry_override WHERE tenant_id = ? ORDER BY 1",
            String.class, workspace);
    }

    /**
     * MIG-231: the Task Registry as a workspace sees it lists that workspace's pipelines as Legacy entries and its own
     * task switches -- never B's pipeline, never B's switch -- and a switch an admin of A makes is A's row alone.
     */
    @Test
    void theTaskRegistryIsMyWorkspacesAndMySwitchIsMine() {
        String before = fx.foreignRows();
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            assertThat(fx.probe("GET pipeline.json/steps/tasks", caller, () -> fx.pipelineSteps.tasks()))
                .contains(SUCCEEDED).contains(A_PIPELINE_ID).doesNotContain(B_PIPELINE_ID).doesNotContain("Bravo Secret Pipeline")
                .doesNotContain("Switched off in this workspace");
        }
        try {
            assertThat(fx.probe("POST pipeline.json/steps/tasks/enabled(mine)", ADMIN_OF_A,
                () -> fx.pipelineSteps.switchTask(switchOf("select", false)))).contains(SUCCEEDED).contains("switched off");
            assertThat(switches(A)).containsExactly("select=false");
            assertThat(fx.probe("GET pipeline.json/steps/tasks(after my switch)", USER_OF_A, () -> fx.pipelineSteps.tasks()))
                .contains("Switched off in this workspace");
        } finally {
            assertThat(fx.probe("POST pipeline.json/steps/tasks/enabled(mine, back to default)", ADMIN_OF_A,
                () -> fx.pipelineSteps.switchTask(switchOf("select", null)))).contains(SUCCEEDED);
        }
        assertThat(switches(A)).isEmpty();
        assertThat(switches(B)).containsExactly("select=false");
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            assertThat(fx.probe("GET pipeline.json/steps/tasks", caller, () -> fx.pipelineSteps.tasks()))
                .doesNotContain(B_PIPELINE_ID).doesNotContain(A_PIPELINE_ID);
            assertThat(fx.probe("POST pipeline.json/steps/tasks/enabled", caller, () -> fx.pipelineSteps.switchTask(switchOf("select", false))))
                .contains(REFUSED);
        }
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("B's switch and pipeline untouched").isEqualTo(before);
    }

    /** The control: A's admin reads A's run's step, its dataset (never its storage key) and its log; the colleague's too. */
    @Test
    void myWorkspacesRunsReadTheirStepsAndLogs() {
        assertThat(fx.probe("GET sourceJob.json/stepExecutions(mine)", ADMIN_OF_A, () -> fx.stepTimeline.stepExecutions(A_RUN, null)))
            .contains(SUCCEEDED).contains("\"legacy\":false").contains("acme step line").contains("\"rowCount\":1")
            .doesNotContain("datasets/");
        assertThat(fx.probe("GET sourceJob.json/stepLogs(mine)", ADMIN_OF_A, () -> fx.stepTimeline.stepLogs(aStep)))
            .contains(SUCCEEDED).contains("acme step line");
        assertThat(fx.probe("GET sourceJob.json/stepExecutions(a colleague's, as admin)", ADMIN_OF_A,
            () -> fx.stepTimeline.stepExecutions(COLLEAGUE_RUN, null))).contains(SUCCEEDED);
        assertThat(fx.probe("GET sourceJob.json/stepExecutions(no such attempt)", ADMIN_OF_A,
            () -> fx.stepTimeline.stepExecutions(A_RUN, 9))).contains(REFUSED).contains("no attempt 9");
        assertThat(fx.leaks).isEmpty();
    }
}
