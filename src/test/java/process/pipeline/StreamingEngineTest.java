package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionOperations;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.data.FileFormats;
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.TaskRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.step;

/**
 * MIG-344: the engine streams the steps whose tasks stream -- read, validate, compute, filter, aggregate, save,
 * upload -- through row files on a real {@link FileDatasetStore}: each step's output is committed only when its try
 * succeeds, spill files go with the try, and a step that holds its input in memory still gets it whole, within the
 * in-memory bounds.
 */
class StreamingEngineTest {

    private static final long TENANT = 2924L;
    private static final long JOB_ID = 2834L;
    private static final long RUN_ID = 7402L;

    @TempDir
    Path root;

    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final ExecutorService tryThreads = Executors.newCachedThreadPool();
    private final AllTasks all = new AllTasks();
    private FileDatasetStore datasets;
    private StepEngine engine;
    private JobQueue run;
    private SourceJob job;
    private BigDataBench.Worker worker;

    @BeforeEach
    void setUp() {
        this.datasets = new FileDatasetStore(this.root.toString());
        StepTasks tasks = new StepTasks(this.all.list());
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN_ID);
        this.run.setJobId(JOB_ID);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Queue);
        this.worker = new BigDataBench.Worker(this.run);
        this.engine = new StepEngine(this.definitions, this.steps, tasks, new DefinitionValidator(new TaskRegistry(tasks,
            new InMemoryTaskOverrideStore())), this.datasets, this.worker, this.transactions, TransactionOperations.withoutTransaction(),
            Executors.newSingleThreadExecutor(), this.tryThreads, duration -> { });
        SourceTask task = new SourceTask();
        task.setPipelineId("ORDERS");
        task.setTaskPayload("<pipeline><x>1</x></pipeline>");
        this.job = new SourceJob();
        this.job.setJobId(JOB_ID);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        this.job.setTaskDetail(task);
        when(this.transactions.findJobQueueByJobQueueId(RUN_ID)).thenAnswer(call -> Optional.of(this.run));
        doAnswer(call -> null).when(this.transactions).saveJobAuditLogs(anyLong(), anyString());
    }

    @AfterEach
    void tearDown() {
        this.tryThreads.shutdownNow();
    }

    private void runs(PipelineDefinition.Step... steps) {
        PipelineDefinition definition = Definitions.of(steps);
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = 1L;
        stored.tenantId = TENANT;
        stored.version = 1;
        stored.json = DefinitionCodec.toJson(definition);
        when(this.definitions.latestFor(TENANT, "ORDERS")).thenReturn(Optional.of(stored));
        when(this.definitions.byId(1L)).thenReturn(Optional.of(stored));
        Optional<StepEngine.StepPlan> plan = this.engine.planFor(this.job, this.run);
        this.run.setJobSend(true);
        this.engine.run(plan.orElseThrow(IllegalStateException::new));
    }

    private void csv(int rows) {
        StringBuilder csv = new StringBuilder("﻿id,region,qty,price,status\n");
        for (int i = 1; i <= rows; i++) {
            csv.append(i).append(',').append(i % 3 == 0 ? "north" : "south").append(',').append(i % 7).append(',')
                .append(i % 100).append(".50,").append(i % 10 == 0 ? "cancelled" : "done").append('\n');
        }
        this.all.buckets.objects.put("in/orders.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> statuses() {
        return this.steps.statuses(RUN_ID, 1);
    }

    private List<Path> files(String suffix) throws Exception {
        try (Stream<Path> walk = Files.walk(this.root)) {
            return walk.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(suffix)).collect(Collectors.toList());
        }
    }

    private static PipelineDefinition.Step read() {
        return step("read", "read_file", config("bucket", "in", "key", "orders.csv"));
    }

    @Test
    void streamingStepsPassRowFilesAndAHoldingStepStillGetsItsInputWhole() throws Exception {
        this.csv(3_000);
        PipelineDefinition.Step pick = step("pick", "select", config("columns", config("region", "r")));
        this.runs(read(),
            step("keep", "filter", config("conditions", Collections.singletonList(config("column", "status", "operator", "ne", "value", "cancelled")))),
            step("calc", "compute", config("formulas", Collections.singletonList(config("target", "total", "expression", "qty * price")))),
            step("save", "save_file", config("fileName", "orders.csv")),
            step("totals", "aggregate", config("groupBy", Collections.singletonList("region"), "aggregations",
                Arrays.asList(config("op", "count", "as", "n"), config("op", "sum", "column", "total", "as", "sum")))),
            pick);

        assertThat(this.worker.last).isEqualTo("Completed: 6 of 6 step(s) completed.");
        assertThat(this.statuses()).containsOnly(entry("read", "Completed"), entry("keep", "Completed"), entry("calc", "Completed"),
            entry("save", "Completed"), entry("totals", "Completed"), entry("pick", "Completed"));
        assertThat(this.steps.row(RUN_ID, 1, "read").recordsOut).isEqualTo(3_000L);
        assertThat(this.steps.row(RUN_ID, 1, "keep").recordsIn).isEqualTo(3_000L);
        assertThat(this.steps.row(RUN_ID, 1, "keep").recordsOut).isEqualTo(2_700L);
        assertThat(this.steps.row(RUN_ID, 1, "save").recordsOut).isEqualTo(2_700L);
        assertThat(this.steps.row(RUN_ID, 1, "totals").recordsIn).as("save passes calc's output on").isEqualTo(2_700L);
        assertThat(this.steps.row(RUN_ID, 1, "pick").recordsOut).isEqualTo(2L);

        // Every output is a row file under its key, recorded with its rows and columns.
        assertThat(this.steps.datasets).extracting(d -> this.steps.storageKeys.get(d.runDatasetId) + ":" + d.rowCount).containsExactly(
            "datasets/7402/1/read/output.rows:3000", "datasets/7402/1/keep/output.rows:2700", "datasets/7402/1/calc/output.rows:2700",
            "datasets/7402/1/save/files/orders.csv:2700", "datasets/7402/1/totals/output.rows:2", "datasets/7402/1/pick/output.rows:2");
        assertThat(this.steps.datasets.get(2).columns).isEqualTo("[\"id\",\"region\",\"qty\",\"price\",\"status\",\"total\"]");
        Dataset calc = this.datasets.read("datasets/7402/1/calc/output.rows");
        assertThat(calc.getRows().get(0)).containsExactly(entry("id", "1"), entry("region", "south"), entry("qty", "1"), entry("price", "1.50"),
            entry("status", "done"), entry("total", 1.5));
        assertThat(this.datasets.read("datasets/7402/1/totals/output.rows").getRows()).extracting(row -> row.get("region") + "=" + row.get("n"))
            .containsExactly("south=1800", "north=900");

        // The saved file is exactly what the in-memory path writes for the same rows.
        byte[] saved = this.datasets.readFile("datasets/7402/1/save/files/orders.csv");
        assertThat(saved).isEqualTo(FileFormats.write(calc, "csv"));
        assertThat(this.steps.outputs.values()).singleElement().satisfies(output -> {
            assertThat(output.byteCount).isEqualTo((long) saved.length);
            assertThat(output.sha256).isEqualTo(RunOutput.sha256Of(saved));
        });

        assertThat(this.steps.logText(RUN_ID, 1, "read")).anyMatch(line -> line.startsWith("INFO 3000 row(s) from in/orders.csv (csv, ")
            && line.endsWith("streamed)."));
        assertThat(this.steps.logText(RUN_ID, 1, "keep")).containsExactly("INFO 2700 of 3000 row(s) kept.",
            this.steps.logText(RUN_ID, 1, "keep").get(1));
        assertThat(this.steps.logText(RUN_ID, 1, "keep").get(1)).startsWith("INFO Streamed in batches of 1,024 row(s): at most about ")
            .endsWith("of the run's 256.0 MB memory budget.");
        assertThat(this.steps.logText(RUN_ID, 1, "pick")).noneMatch(line -> line.contains("Streamed"));
        assertThat(this.files(".partial")).as("no half-written file").isEmpty();
        assertThat(this.root.resolve("scratch")).as("no spill left").satisfiesAnyOf(
            dir -> assertThat(dir).doesNotExist(), dir -> assertThat(dir).isEmptyDirectory());
    }

    @Test
    void aFailedStreamingTryLeavesNoOutputAndTheRunFails() throws Exception {
        this.csv(3_000);
        StringBuilder bad = new StringBuilder(new String(this.all.buckets.objects.get("in/orders.csv"), StandardCharsets.UTF_8));
        bad.append("3001,north,lots,1.00,done\n");
        this.all.buckets.objects.put("in/orders.csv", bad.toString().getBytes(StandardCharsets.UTF_8));
        this.runs(read(), step("calc", "compute", config("formulas", Collections.singletonList(config("target", "total", "expression", "qty * price")))));

        assertThat(this.statuses()).containsOnly(entry("read", "Completed"), entry("calc", "Failed"));
        assertThat(this.worker.last).startsWith("Failed: Step 2 <calc> failed: Row 3001, total:");
        assertThat(this.files("/calc/output.rows")).as("no output for the failed step").isEmpty();
        assertThat(this.files(".partial")).isEmpty();
        assertThat(this.files(".rows")).hasSize(1);
    }

    @Test
    void aReadStreamsPastTheOldCapAndAHoldingStepSaysWhyItCannotTakeIt() throws Exception {
        this.csv(50_001);
        this.runs(read(), step("pick", "select", config("columns", config("id", "id"))));

        assertThat(this.steps.row(RUN_ID, 1, "read").recordsOut).as("past the 50,000 rows a step held before MIG-344").isEqualTo(50_001L);
        assertThat(this.statuses()).containsOnly(entry("read", "Completed"), entry("pick", "Failed"));
        assertThat(this.worker.last).isEqualTo("Failed: Step 2 <pick> failed: The step's input has 50,001 rows x 5 columns; a step that "
            + "holds its whole input in memory takes at most 50,000 rows and 1,000,000 cells. Put a filter, select or aggregate before it.");
    }
}
