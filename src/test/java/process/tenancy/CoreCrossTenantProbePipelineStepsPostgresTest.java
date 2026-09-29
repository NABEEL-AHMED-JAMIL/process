package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.pipeline.PipelineDefinitionService;

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

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_pipeline_steps");
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 1, ?::json)", B_PIPELINE, B_DEFINITION);
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
}
