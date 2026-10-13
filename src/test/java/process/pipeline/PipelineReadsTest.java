package process.pipeline;

import org.junit.jupiter.api.Test;
import process.pipeline.backing.Fakes;
import process.pipeline.backing.IntegrationNames;
import process.pipeline.registry.TaskKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UX review 2026-10-13 (I1): Connections › Sources lists what the workspace's pipelines read, from their latest
 * definitions and the Task Registry -- a step reads a place when its schema says a config property names one (a bucket,
 * an API request, a database connection, a document type) and it is not an Output; a reader naming no place reads the
 * run's own file or rows written in the step. Grouped by place, named by the services that own them.
 */
class PipelineReadsTest {

    private static final long TENANT = 2960L;

    private final PipelineDefinitionStore store = mock(PipelineDefinitionStore.class);
    private final PipelineReads reads = new PipelineReads(this.store, new AllTasks().tasks());
    private final List<PipelineDefinitionStore.TaskDefinition> pipelines = new ArrayList<>();

    private void pipeline(long taskDetailId, String name, long pipelineKey, String stepsJson) {
        PipelineDefinitionStore.TaskDefinition task = new PipelineDefinitionStore.TaskDefinition();
        task.taskDetailId = taskDetailId;
        task.taskName = name;
        task.pipelineId = "P" + pipelineKey;
        task.stored = new PipelineDefinitionStore.Stored();
        task.stored.id = pipelineKey * 10;
        task.stored.tenantId = TENANT;
        task.stored.pipelineKey = pipelineKey;
        task.stored.version = 1;
        task.stored.json = "{\"version\":1,\"steps\":[" + stepsJson + "]}";
        this.pipelines.add(task);
        when(this.store.latestForTasks(eq(TENANT), isNull())).thenReturn(this.pipelines);
    }

    private static String step(String key, String task, String config) {
        return "{\"key\":\"" + key + "\",\"task\":\"" + task + "\",\"config\":" + config + "}";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<String> readers(Map<String, Object> place) {
        return ((List<Map<String, Object>>) place.get("pipelines")).stream()
            .map(p -> p.get("taskName") + ":" + String.join("+", (List<String>) p.get("steps"))).collect(Collectors.toList());
    }

    private void healthcare() {
        pipeline(1928, "Brain MRI grading", 100470, step("manifest", "read_file",
            "{\"bucket\":\"riverside-s3\",\"key\":\"datasets/brain-mri/manifest.csv\",\"format\":\"csv\"}")
            + "," + step("spine", "sample", "{\"rows\":[{\"x\":1}]}")
            + "," + step("save", "upload_bucket", "{\"bucket\":\"riverside-s3\",\"key\":\"out/brain.csv\"}"));
        pipeline(1932, "Patient 360", 100478, step("patients", "read_api", "{\"requestId\":1083,\"environmentId\":1072}")
            + "," + step("a1c", "read_api", "{\"requestId\":1084}") + "," + step("bmi", "read_api", "{\"requestId\":1084}")
            + "," + step("rxnorm", "enrich", "{\"requestId\":1087,\"into\":\"rx\"}"));
        pipeline(1934, "Drug safety", 100482, step("meds", "read_file",
            "{\"bucket\":\"riverside-s3\",\"key\":\"datasets/brain-mri/manifest.csv\"}")
            + "," + step("labs", "read_database", "{\"connectionId\":12,\"query\":\"select 1\"}")
            + "," + step("write", "write_database", "{\"connectionId\":12,\"table\":\"t\"}"));
        pipeline(1935, "Trial finder", 100484, step("intake", "read_file", "{\"format\":\"json\"}")
            + "," + step("studies", "read_s3", "{\"bucket\":\"riverside-s3\",\"prefix\":\"labs/results/\"}"));
        pipeline(2009, "Approved invoices", 100672, step("approved", "read_documents", "{\"documentTypeId\":1008}"));
        pipeline(2010, "Legacy one", 100700, step("legacy", "legacy", "{\"pipelineId\":\"F768930\"}"));
        pipeline(2011, "Transform only", 100702, step("pick", "select", "{\"columns\":[\"a\"]}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyPlaceAPipelineReadsIsListedOnceWithTheStepsThatReadIt() {
        healthcare();
        Map<String, Object> answer = this.reads.forWorkspace(TENANT);
        List<Map<String, Object>> places = data(answer);

        assertThat(places).extracting(p -> p.get("kind") + " " + p.get("label")).containsExactly(
            "storage riverside-s3 › datasets/brain-mri/manifest.csv",
            "storage riverside-s3 › labs/results/",
            "storage The file that started the run",
            "api API request 1083",
            "api API request 1084",
            "api API request 1087",
            "database Database connection 12",
            "documents Document type 1008",
            "other Sample rows (written in the step)");
        assertThat(readers(places.get(0))).as("one bucket and path, read by two pipelines")
            .containsExactly("Brain MRI grading:manifest", "Drug safety:meds");
        assertThat(readers(places.get(4))).as("two steps of one pipeline on one request").containsExactly("Patient 360:a1c+bmi");
        assertThat(readers(places.get(5))).as("Enrich calls an API per row: it reads").containsExactly("Patient 360:rxnorm");
        assertThat(places.get(0)).containsEntry("task", "read_file").containsEntry("taskName", "Read CSV/JSON/Parquet")
            .containsEntry("link", "/connections/storage");
        assertThat((Map<String, Object>) places.get(0).get("target")).containsEntry("bucket", "riverside-s3")
            .containsEntry("path", "datasets/brain-mri/manifest.csv");
        assertThat((Map<String, Object>) places.get(6).get("target")).containsEntry("connectionId", 12L);
        assertThat(places.get(8)).doesNotContainKey("link");
        // Output steps (upload_bucket, write_database) write, they do not read; Select and the legacy step read nothing.
        assertThat(answer).containsEntry("pipelines", 6).containsEntry("reading", 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void namesComeFromTheServicesThatOwnThemAndAFailureKeepsTheIds() {
        healthcare();
        this.reads.useIntegration(new IntegrationNames() {
            @Override
            public Names describe(long tenantId, Collection<Long> requestIds, Collection<Long> connectionIds) {
                assertThat(tenantId).isEqualTo(TENANT);
                assertThat(requestIds).containsExactly(1083L, 1084L, 1087L);
                assertThat(connectionIds).containsExactly(12L);
                Names names = new Names();
                RequestName patients = new RequestName();
                patients.name = "Patients";
                patients.collectionId = 1079L;
                patients.collectionName = "FHIR sandbox";
                names.requests.put(1083L, patients);
                names.connections.put(12L, "Lab warehouse");
                return names;
            }
        });
        Fakes.Documents documents = new Fakes.Documents();
        this.reads.useDocuments(documents);
        List<Map<String, Object>> places = data(this.reads.forWorkspace(TENANT));

        Map<String, Object> patients = places.stream()
            .filter(p -> Long.valueOf(1083L).equals(((Map<String, Object>) p.get("target")).get("requestId"))).findFirst().get();
        assertThat(patients).containsEntry("label", "FHIR sandbox › Patients").containsEntry("link", "/connections/api-collections/1079");
        assertThat((Map<String, Object>) patients.get("target")).containsEntry("requestId", 1083L).containsEntry("collectionId", 1079L);
        assertThat(places).extracting(p -> (String) p.get("label")).contains("API request 1084", "Lab warehouse");
        // The type is read as Core names a type elsewhere: one row, no names, no run (so nothing is billed).
        assertThat(documents.requests).hasSize(1);
        assertThat(documents.requests.get(0).limit).isEqualTo(1);
        assertThat(documents.requests.get(0).jobQueueId).isNull();

        this.reads.useIntegration((tenantId, requestIds, connectionIds) -> {
            throw new IOException("integration-service is down");
        });
        assertThat(data(this.reads.forWorkspace(TENANT))).extracting(p -> (String) p.get("label"))
            .contains("API request 1083", "Database connection 12");
    }

    @Test
    void aWorkspaceWithoutStepEnginePipelinesReadsNothing() {
        pipeline(2010, "Legacy one", 100700, step("legacy", "legacy", "{\"pipelineId\":\"F768930\"}"));
        Map<String, Object> answer = this.reads.forWorkspace(TENANT);
        assertThat(data(answer)).isEmpty();
        assertThat(answer).containsEntry("pipelines", 0).containsEntry("reading", 0);
    }

    @Test
    void anUnreadableDefinitionIsSkippedNotThrown() {
        pipeline(1928, "Broken", 100470, "{\"key\":");
        pipeline(1932, "Fine", 100478, step("patients", "read_api", "{\"requestId\":1083}"));
        assertThat(data(this.reads.forWorkspace(TENANT))).extracting(p -> (String) p.get("label")).containsExactly("API request 1083");
    }

    @Test
    void theKindsComeFromTheSchemaFormatsNotTaskNames() {
        assertThat(PipelineReads.PLACES.keySet()).containsExactly("bucket", "api-request", "db-connection", "document-type");
        // Every READ task of the registry is one the reads can describe: a place, the run's file, or rows in the step.
        for (StepTask task : new AllTasks().list()) {
            if (task.spec().kind() == TaskKind.READ) {
                PipelineDefinition.Step bare = Definitions.step("s", task.code());
                assertThat(this.reads.placesOf(bare)).as(task.code()).hasSize(1);
            }
        }
        assertThat(this.reads.placesOf(Definitions.step("s", "no_such_task"))).isEmpty();
        assertThat(this.reads.placesOf(Definitions.step("s", "upload_bucket", Definitions.config("bucket", "b", "key", "k")))).isEmpty();
        assertThat(Arrays.asList(PipelineReads.STORAGE, PipelineReads.API, PipelineReads.DATABASE, PipelineReads.DOCUMENTS,
            PipelineReads.OTHER)).doesNotHaveDuplicates();
    }
}
