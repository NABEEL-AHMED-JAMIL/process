package process.pipeline;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.repository.PipelineRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Console review 2026-10-07 (M12, H5): which pipelines use a prompt or an API request, counted from the step-engine
 * definitions as well as the old AI fields -- on a real changelog, under row-level security. A number that is no reference
 * (a row limit that equals a request id) does not count, an older definition version does not count, nor does a deleted
 * pipeline; another workspace's pipeline counts for a prompt (ai-service asks across workspaces) but never for a request.
 */
class PipelineUsagePostgresTest {

    private static final long TENANT = 7951L;
    private static final long OTHER = 7952L;
    private static final long PROMPT = 4501L;
    private static final long REQUEST = 4601L;
    private static final long OTHER_REQUEST = 4602L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static JdbcTemplate login;
    private static PipelineUsage usage;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("pipeline_usage");
        login = db.jdbc();
        jpa = new ScratchJpa(db.appPool());
        for (long tenant : new long[] {TENANT, OTHER}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant,
                "T" + tenant);
        }
        // An old pipeline with an AI field on the prompt.
        pipeline(79511, TENANT, "OLD-AI", "Active");
        login.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, field_type, required, position, prompt_id, "
            + "tenant_id) VALUES (795111, 79511, 'summary', 'Summary', 'ai', false, 0, ?, ?)", PROMPT, TENANT);
        // A step-engine pipeline: an image AI step on the prompt, two Read API steps on the request (one pinned to v3), and
        // an Enrich on the other request.
        pipeline(79512, TENANT, "ENGINE", "Active");
        definition(79512, 1, "{\"version\":1,\"steps\":[{\"key\":\"a\",\"task\":\"sample\",\"config\":{\"rows\":[{\"x\":1}]}}]}");
        definition(79512, 2, "{\"version\":1,\"steps\":["
            + "{\"key\":\"patients\",\"task\":\"read_api\",\"config\":{\"requestId\":" + REQUEST + "}},"
            + "{\"key\":\"more\",\"task\":\"read_api\",\"config\":{\"requestId\":" + REQUEST + ",\"version\":3}},"
            + "{\"key\":\"drugs\",\"task\":\"enrich\",\"config\":{\"requestId\":" + OTHER_REQUEST + ",\"into\":\"d\"}},"
            + "{\"key\":\"label\",\"task\":\"ai_prompt\",\"config\":{\"promptId\":" + PROMPT + ",\"image\":{\"bucket\":\"b\",\"keyColumn\":\"k\"}}}]}");
        // The number appears, but as a row limit: no reference.
        pipeline(79513, TENANT, "LOOKALIKE", "Active");
        definition(79513, 1, "{\"version\":1,\"steps\":[{\"key\":\"a\",\"task\":\"sample\",\"config\":{\"rows\":[{\"n\":" + REQUEST
            + "}],\"limit\":" + PROMPT + "}}]}");
        // An older version named the request; the latest no longer does.
        pipeline(79514, TENANT, "MOVED-ON", "Active");
        definition(79514, 1, "{\"version\":1,\"steps\":[{\"key\":\"r\",\"task\":\"read_api\",\"config\":{\"requestId\":" + REQUEST + "}}]}");
        definition(79514, 2, "{\"version\":1,\"steps\":[{\"key\":\"a\",\"task\":\"sample\",\"config\":{\"rows\":[{\"x\":1}]}}]}");
        // A deleted pipeline still naming both.
        pipeline(79515, TENANT, "GONE", "Delete");
        definition(79515, 1, "{\"version\":1,\"steps\":[{\"key\":\"r\",\"task\":\"read_api\",\"config\":{\"requestId\":" + REQUEST + "}},"
            + "{\"key\":\"p\",\"task\":\"ai_prompt\",\"config\":{\"promptId\":" + PROMPT + "}}]}");
        // Another workspace's pipeline naming both (ids are unique platform-wide; this is only a test of the scoping).
        pipeline(79521, OTHER, "THEIRS", "Active");
        definition(79521, 1, "{\"version\":1,\"steps\":[{\"key\":\"r\",\"task\":\"read_api\",\"config\":{\"requestId\":" + REQUEST + "}},"
            + "{\"key\":\"p\",\"task\":\"ai_prompt\",\"config\":{\"promptId\":" + PROMPT + "}}]}");
        usage = new PipelineUsage(jpa.repository(PipelineRepository.class), new PipelineDefinitionStore(db.appJdbc()),
            new StepReferences(new AllTasks().tasks()));
    }

    private static void pipeline(long key, long tenant, String id, String status) {
        login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status) VALUES (?, ?, ?, ?, ?)", key, tenant,
            id, id + " name", status);
    }

    private static void definition(long key, int version, String json) {
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, ?, ?::json)", key, version, json);
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void aPromptIsUsedByOldAiFieldsAndStepEngineAiStepsOfEveryWorkspace() {
        long count = RowSecurity.acrossTenants("test: ai-service asks about one prompt", () -> usage.countUsingPrompt(PROMPT));
        assertThat(count).isEqualTo(3);   // OLD-AI, ENGINE, THEIRS -- not LOOKALIKE, not GONE
        assertThat(RowSecurity.acrossTenants("test", () -> usage.countUsingPrompt(9999L))).isZero();
    }

    @Test
    void aWorkspacesApiRequestsAreUsedByItsLatestDefinitionsOnly() {
        List<Map<String, Object>> users = RowSecurity.forTenant(TENANT, () -> usage.apiRequestUsers(TENANT, Arrays.asList(REQUEST, OTHER_REQUEST)));

        assertThat(users).extracting(u -> u.get("pipelineId") + ":" + u.get("requestId"))
            .containsExactly("ENGINE:" + REQUEST, "ENGINE:" + OTHER_REQUEST);
        Map<String, Object> patients = users.get(0);
        assertThat(patients).containsEntry("pipelineName", "ENGINE name").containsEntry("definitionVersion", 2)
            .containsEntry("version", 3).containsEntry("unpinned", true);
        assertThat(patients.get("steps")).isEqualTo(Arrays.asList("patients", "more"));
        assertThat(users.get(1)).containsEntry("version", null).containsEntry("unpinned", true);
    }

    @Test
    void anotherWorkspacesPipelinesAreNeverAnApiRequestsUsers() {
        List<Map<String, Object>> users = RowSecurity.forTenant(OTHER, () -> usage.apiRequestUsers(OTHER, Collections.singletonList(REQUEST)));
        assertThat(users).extracting(u -> u.get("pipelineId")).containsExactly("THEIRS");
        // Asked as one workspace about the other's: row security shows none of its definitions.
        assertThat(RowSecurity.forTenant(OTHER, () -> usage.apiRequestUsers(TENANT, Collections.singletonList(REQUEST)))).isEmpty();
    }
}
