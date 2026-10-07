package process.pipeline;

import com.sun.management.GarbageCollectionNotificationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.support.TransactionOperations;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.service.NotifyService;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.BucketStore;
import process.pipeline.backing.ContractChecker;
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.tasks.AggregateStepTask;
import process.pipeline.tasks.ComputeStepTask;
import process.pipeline.tasks.FilterStepTask;
import process.pipeline.tasks.JoinStepTask;
import process.pipeline.tasks.ReadFileStepTask;
import process.pipeline.tasks.SaveFileStepTask;
import process.pipeline.tasks.SelectStepTask;
import process.pipeline.tasks.TransformStepTask;
import process.pipeline.tasks.UploadBucketStepTask;
import process.pipeline.tasks.ValidateStepTask;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.sun.management.ThreadMXBean;
import java.time.LocalDateTime;
import javax.management.NotificationListener;
import process.pipeline.data.Values;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.step;

/**
 * MIG-344: the big-data harness. Not a test: it runs only with -Dbigdata.bench=engine|phases, one JVM at a time, e.g.
 *
 * <pre>
 * mvn -o test -Dtest=BigDataBench -Dbigdata.bench=engine -Dbigdata.files=/path/a.csv,/path/b.csv \
 *     -Dbigdata.profile=orders -Dbigdata.out=/scratch/results -DargLine="-Xmx2g ..."
 * </pre>
 *
 * <b>engine</b>: the real StepEngine runs a pipeline over the file (read_file, validate, compute, filter, aggregate,
 * join, save_file, upload_bucket for the "orders" profile), on a FileDatasetStore, with fakes only for storage-service
 * and integration-service (the bucket is the local file; the contract check is in-process). The heap is sampled every
 * millisecond and every GC's after-collection heap is recorded; each step's window runs from the end of the step before
 * it (so it includes reading its input back) to its own end.
 *
 * <b>phases</b>: the same steps, called in this thread as the engine calls them -- read the input back, run, keep the
 * output -- each phase measured on its own (time, bytes allocated, live heap), plus the retained size of each dataset.
 * This is where the memory goes.
 */
@EnabledIfSystemProperty(named = "bigdata.bench", matches = ".+")
class BigDataBench {

    static final long TENANT = 2924L;
    static final long JOB_ID = 99001L;
    static final long DEFINITION_ID = 99002L;
    static final AtomicLong RUN_IDS = new AtomicLong(990000L);
    static final double MB = 1024.0 * 1024.0;

    // ---------------------------------------------------------------------------------------------------- the pipeline

    /** The steps of a profile over one file. */
    static List<PipelineDefinition.Step> pipeline(String profile, String file) {
        List<PipelineDefinition.Step> steps = new ArrayList<>();
        steps.add(step("read", "read_file", config("bucket", "bench", "key", file, "format", "csv")));
        switch (profile) {
            case "orders":
                steps.add(step("valid", "validate", config("contractName", "orders", "onInvalid", "drop")));
                steps.add(step("calc", "compute", config("formulas", Arrays.asList(
                    config("target", "line_total", "expression", "round(quantity * unit_price * (1 - discount_pct / 100), 2)"),
                    config("target", "band", "expression", "if(line_total > 1000, 'large', if(line_total > 100, 'medium', 'small'))")))));
                steps.add(step("keep", "filter", config("conditions", Collections.singletonList(
                    config("column", "status", "operator", "ne", "value", "cancelled")))));
                steps.add(step("totals", "aggregate", config("groupBy", Arrays.asList("region", "category"), "aggregations", Arrays.asList(
                    config("op", "count", "as", "orders"),
                    config("op", "sum", "column", "line_total", "as", "revenue"),
                    config("op", "avg", "column", "score", "as", "avg_score"),
                    config("op", "count_distinct", "column", "customer_id", "as", "customers"),
                    config("op", "max", "column", "unit_price", "as", "max_price")))));
                PipelineDefinition.Step join = step("joined", "join", config("with", "totals", "on", Arrays.asList(
                    config("left", "region", "right", "region"), config("left", "category", "right", "category"))));
                join.setInput("keep");
                steps.add(join);
                steps.add(step("save", "save_file", config("fileName", "orders-out.csv", "format", "csv")));
                steps.add(step("upload", "upload_bucket", config("bucket", "bench", "key", "exports/{{run}}.csv", "format", "csv")));
                break;
            case "orders-stream":
                // The streaming phase's pipeline: read -> validate -> compute -> filter -> save + upload (the big rows) ->
                // two aggregates (one with few groups and big distinct sets, one with 200,000 groups).
                steps.add(step("valid", "validate", config("contractName", "orders", "onInvalid", "drop")));
                steps.add(step("calc", "compute", config("formulas", Arrays.asList(
                    config("target", "line_total", "expression", "round(quantity * unit_price * (1 - discount_pct / 100), 2)"),
                    config("target", "band", "expression", "if(line_total > 1000, 'large', if(line_total > 100, 'medium', 'small'))")))));
                steps.add(step("keep", "filter", config("conditions", Collections.singletonList(
                    config("column", "status", "operator", "ne", "value", "cancelled")))));
                steps.add(step("save", "save_file", config("fileName", "orders-out.csv", "format", "csv")));
                steps.add(step("upload", "upload_bucket", config("bucket", "bench", "key", "exports/{{run}}.csv", "format", "csv")));
                steps.add(step("totals", "aggregate", config("groupBy", Arrays.asList("region", "category"), "aggregations", Arrays.asList(
                    config("op", "count", "as", "orders"),
                    config("op", "sum", "column", "line_total", "as", "revenue"),
                    config("op", "avg", "column", "score", "as", "avg_score"),
                    config("op", "count_distinct", "column", "customer_id", "as", "customers"),
                    config("op", "max", "column", "unit_price", "as", "max_price")))));
                PipelineDefinition.Step customers = step("custs", "aggregate", config("groupBy", Collections.singletonList("customer_id"),
                    "aggregations", Arrays.asList(config("op", "count", "as", "orders"), config("op", "sum", "column", "line_total", "as", "spent"),
                        config("op", "count_distinct", "column", "category", "as", "categories"), config("op", "last", "column", "event_date", "as", "last_seen"))));
                customers.setInput("keep");
                steps.add(customers);
                break;
            case "creditcard":
                steps.add(step("calc", "compute", config("formulas", Collections.singletonList(
                    config("target", "amount_band", "expression", "if(Amount > 1000, 'large', if(Amount > 100, 'medium', 'small'))")))));
                steps.add(step("keep", "filter", config("conditions", Collections.singletonList(
                    config("column", "Amount", "operator", "gt", "value", 0)))));
                steps.add(step("totals", "aggregate", config("groupBy", Arrays.asList("Class", "amount_band"), "aggregations", Arrays.asList(
                    config("op", "count", "as", "rows"), config("op", "sum", "column", "Amount", "as", "amount"),
                    config("op", "avg", "column", "V1", "as", "avg_v1")))));
                steps.add(step("save", "save_file", config("fileName", "creditcard-out.csv", "format", "csv")));
                break;
            case "medical":
                // BRFSS / readmission in full: read, a filter, a grouping, the rows kept as a file.
                steps.add(step("keep", "filter", config("conditions", Collections.singletonList(
                    config("column", System.getProperty("bigdata.filter-column", "Age"), "operator", "not_empty")))));
                steps.add(step("save", "save_file", config("fileName", "medical-out.csv", "format", "csv")));
                steps.add(step("totals", "aggregate", config("groupBy", Collections.singletonList(System.getProperty("bigdata.group-column", "Age")),
                    "aggregations", Collections.singletonList(config("op", "count", "as", "rows")))));
                break;
            default:
                throw new IllegalArgumentException("profile " + profile);
        }
        return steps;
    }

    // ---------------------------------------------------------------------------------------------------- fakes

    /** The bucket is the local disk: the key is the file's path. Reads as TrustedBucketStore does (whole, through a buffer). */
    static final class LocalBuckets implements BucketStore {
        final AtomicLong uploaded = new AtomicLong();

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public Listing list(long tenantId, String bucket, String prefix, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] read(long tenantId, String bucket, String key, long maxBytes) throws IOException {
            try (InputStream in = new FileInputStream(key)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    out.write(chunk, 0, n);
                    if (out.size() > maxBytes) {
                        throw new IllegalStateException(String.format("%s is more than %,d bytes, the most a step reads.", key, maxBytes));
                    }
                }
                return out.toByteArray();
            }
        }

        @Override
        public InputStream open(long tenantId, String bucket, String key, long maxBytes) throws IOException {
            if (Files.size(Paths.get(key)) > maxBytes) {
                throw new IllegalStateException(String.format("%s is more than %,d bytes, the most a step reads.", key, maxBytes));
            }
            return new FileInputStream(key);
        }

        @Override
        public Streamed stream(long tenantId, String bucket, String key) throws IOException {
            return new Streamed(new FileInputStream(key), Files.size(Paths.get(key)), "text/csv");
        }

        @Override
        public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
            this.uploaded.addAndGet(content.length);
        }

        /** A streamed upload (MIG-344): the bytes are drained and counted, as storage-service's Counted does. */
        @Override
        public void upload(long tenantId, String bucket, String key, InputStream content, long size, String contentType) throws IOException {
            byte[] chunk = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = content.read(chunk)) != -1) {
                total += n;
            }
            this.uploaded.addAndGet(total);
        }
    }

    /** integration-service's contract check, in-process: an order holds when its quantity and price are numbers. */
    static final class LocalContracts implements ContractChecker {
        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public ContractVerdicts validate(ContractCall call) {
            ContractVerdicts answer = new ContractVerdicts();
            answer.name = "orders";
            answer.version = 1;
            answer.rows = new ArrayList<>(call.rows.size());
            for (int i = 0; i < call.rows.size(); i++) {
                Map<String, Object> row = call.rows.get(i);
                boolean valid = numeric(row.get("quantity")) && numeric(row.get("unit_price"));
                answer.rows.add(new RowVerdict(i, valid, valid ? null : Collections.singletonList("/quantity: must be a number")));
            }
            return answer;
        }

        private static boolean numeric(Object value) {
            return value != null && Values.number(value) != null;
        }
    }

    static List<StepTask> tasks(LocalBuckets buckets) {
        return new ArrayList<>(Arrays.asList(new ReadFileStepTask(buckets), new ValidateStepTask(new LocalContracts()),
            new ComputeStepTask(), new FilterStepTask(), new AggregateStepTask(), new JoinStepTask(), new SaveFileStepTask(),
            new UploadBucketStepTask(buckets), new SelectStepTask(), new TransformStepTask()));
    }

    // ---------------------------------------------------------------------------------------------------- measuring

    /** Heap sampled every millisecond, and every GC's heap after collection: the peak since the last mark. */
    static final class HeapWatch implements AutoCloseable {
        private final AtomicLong peakUsed = new AtomicLong();
        private final AtomicLong peakLive = new AtomicLong();
        private final AtomicLong gcs = new AtomicLong();
        private final Thread sampler;
        private volatile boolean stop;
        private final List<NotificationEmitter> emitters = new ArrayList<>();
        private final NotificationListener listener = (notification, handback) -> {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                return;
            }
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            long after = 0;
            for (MemoryUsage usage : info.getGcInfo().getMemoryUsageAfterGc().values()) {
                after += usage.getUsed();
            }
            this.gcs.incrementAndGet();
            this.peakLive.accumulateAndGet(after, Math::max);
        };

        HeapWatch() {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter) {
                    ((NotificationEmitter) gc).addNotificationListener(this.listener, null, null);
                    this.emitters.add((NotificationEmitter) gc);
                }
            }
            this.sampler = new Thread(() -> {
                while (!this.stop) {
                    this.peakUsed.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
            }, "heap-watch");
            this.sampler.setDaemon(true);
            this.sampler.start();
        }

        /** {peak used, peak live after GC, GCs} since the last mark; starts the next window. */
        long[] mark() {
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long[] out = {Math.max(this.peakUsed.getAndSet(used), used), this.peakLive.getAndSet(0), this.gcs.getAndSet(0)};
            return out;
        }

        @Override
        public void close() {
            this.stop = true;
            for (NotificationEmitter emitter : this.emitters) {
                try {
                    emitter.removeNotificationListener(this.listener);
                } catch (Exception ignored) {
                    // gone
                }
            }
        }
    }

    static long retained() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static long allocated() {
        return ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static long allocated(Thread thread) {
        return ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(thread.getId());
    }

    /** The JVM's resident set (heap, metaspace, threads and any native memory such as DuckDB's), from ps. */
    static long rssBytes() {
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(ProcessHandle.current().pid())).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                return Long.parseLong(reader.readLine().trim()) * 1024;
            }
        } catch (Exception ex) {
            return -1;
        }
    }

    static final List<String> REPORT = new ArrayList<>();

    static void report(String line) {
        System.out.println("[bench] " + line);
        REPORT.add(line);
    }

    static void flush(String name) throws IOException {
        String dir = System.getProperty("bigdata.out");
        if (dir == null) {
            return;
        }
        Files.createDirectories(Paths.get(dir));
        Files.write(Paths.get(dir, name), REPORT, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        REPORT.clear();
    }

    static List<String> files() {
        return Arrays.stream(System.getProperty("bigdata.files", "").split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    static String label() {
        return System.getProperty("bigdata.label", "run");
    }

    // ---------------------------------------------------------------------------------------------------- engine mode

    @Test
    @EnabledIfSystemProperty(named = "bigdata.bench", matches = "engine")
    void engine() throws Exception {
        String profile = System.getProperty("bigdata.profile", "orders");
        Path root = Paths.get(System.getProperty("bigdata.datasets", System.getProperty("java.io.tmpdir") + "/bigdata-datasets"));
        report(String.format("## %s, engine, profile %s, -Xmx %.0f MB, %s", label(), profile, Runtime.getRuntime().maxMemory() / MB,
            LocalDateTime.now().withNano(0)));
        report("| file | rows in | step | task | records in | records out | time s | rows/s | peak heap used MB | peak live (after GC) MB | GCs | RSS MB | status |");
        report("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (String file : files()) {
            this.engineRun(profile, file, root);
            flush("engine.md");
        }
    }

    private void engineRun(String profile, String file, Path root) throws Exception {
        long lines = countLines(file) - 1;
        String name = Paths.get(file).getFileName().toString();
        LocalBuckets buckets = new LocalBuckets();
        StepTasks tasks = new StepTasks(tasks(buckets));
        FileDatasetStore datasets = new FileDatasetStore(root.toString());
        long runId = RUN_IDS.incrementAndGet();
        JobQueue run = new JobQueue();
        run.setJobQueueId(runId);
        run.setJobId(JOB_ID);
        run.setTenantId(TENANT);
        run.setAttempt(1);
        run.setJobStatus(JobStatus.Queue);
        SourceTask task = new SourceTask();
        task.setPipelineId("BENCH");
        task.setTaskPayload("<pipeline><x>1</x></pipeline>");
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
        TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
        when(transactions.findJobQueueByJobQueueId(runId)).thenAnswer(call -> Optional.of(run));
        doAnswer(call -> null).when(transactions).saveJobAuditLogs(anyLong(), anyString());
        PipelineDefinition definition = Definitions.of(pipeline(profile, file).toArray(new PipelineDefinition.Step[0]));
        if (definition.getSettings() == null) {
            definition.setSettings(new PipelineDefinition.Settings());
        }
        definition.getSettings().setDatasetRetentionHours(1);
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = DEFINITION_ID;
        stored.tenantId = TENANT;
        stored.version = 1;
        stored.json = DefinitionCodec.toJson(definition);
        when(definitions.latestFor(TENANT, "BENCH")).thenReturn(Optional.of(stored));
        when(definitions.byId(DEFINITION_ID)).thenReturn(Optional.of(stored));

        HeapWatch watch = new HeapWatch();
        List<String[]> table = new ArrayList<>();
        AtomicReference<Long> windowStart = new AtomicReference<>(System.nanoTime());
        Map<Long, Long> recordsIn = new LinkedHashMap<>();
        InMemoryStepStore steps = new InMemoryStepStore() {
            @Override
            public synchronized void started(long id, Long in) {
                super.started(id, in);
                recordsIn.put(id, in);
            }

            @Override
            public synchronized void ended(long id, String status, Long recordsOut, String errorJson, String message) {
                super.ended(id, status, recordsOut, errorJson, message);
                long now = System.nanoTime();
                long[] heap = watch.mark();
                double seconds = (now - windowStart.getAndSet(now)) / 1e9;
                StepRow row = this.rows.get(id);
                Long in = recordsIn.get(id);
                long work = Math.max(in == null ? 0 : in, recordsOut == null ? 0 : recordsOut);
                table.add(new String[] {name, String.valueOf(lines), row.stepKey, row.taskCode, String.valueOf(in), String.valueOf(recordsOut),
                    String.format("%.2f", seconds), String.format("%,.0f", work / Math.max(seconds, 1e-6)),
                    String.format("%.0f", heap[0] / MB), String.format("%.0f", heap[1] / MB), String.valueOf(heap[2]),
                    String.format("%.0f", rssBytes() / MB), status + (errorJson == null ? "" : " " + errorJson.replace('|', '/'))});
            }

            @Override
            public synchronized void notRun(long id, String status, String message) {
                super.notRun(id, status, message);
                StepRow row = this.rows.get(id);
                table.add(new String[] {name, String.valueOf(lines), row.stepKey, row.taskCode, "", "", "", "", "", "", "", "", status});
            }
        };
        NotifyService worker = new Worker(run);
        ExecutorService tryThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bench-try");
            thread.setDaemon(true);
            return thread;
        });
        AtomicReference<Thread> tryThreadRef = new AtomicReference<>();
        tryThread.submit(() -> tryThreadRef.set(Thread.currentThread())).get();
        StepEngine engine = new StepEngine(definitions, steps, tasks, new DefinitionValidator(new TaskRegistry(tasks, new InMemoryTaskOverrideStore())),
            datasets, worker, transactions, TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(), tryThread,
            duration -> { });
        engine.useMemory(Integer.getInteger("bigdata.run-memory-mb", 256), Integer.getInteger("bigdata.batch-rows", 1024));
        long allocated0 = allocated(tryThreadRef.get()) + allocated();
        long began = System.nanoTime();
        windowStart.set(began);
        watch.mark();
        Optional<StepEngine.StepPlan> plan = engine.planFor(job, run);
        run.setJobSend(true);
        try {
            engine.run(plan.orElseThrow(() -> new IllegalStateException("no plan")));
        } catch (OutOfMemoryError oom) {
            table.add(new String[] {name, String.valueOf(lines), "?", "?", "", "", "", "", "", "", "", "", "OutOfMemoryError"});
        }
        double total = (System.nanoTime() - began) / 1e9;
        long allocated = allocated(tryThreadRef.get()) + allocated() - allocated0;
        watch.close();
        tryThread.shutdownNow();
        for (String[] row : table) {
            report("| " + String.join(" | ", row) + " |");
        }
        String last = ((Worker) worker).last;
        report(String.format("| %s | %d | **run** | | | | %.2f | %,.0f | | | | %.0f | %s; %,.0f MB allocated on the try thread; uploaded %,d bytes |",
            name, lines, total, lines / total, rssBytes() / MB, last == null ? "?" : last.replace('|', '/'), allocated / MB, buckets.uploaded.get()));
        for (StepStore.StepRow step : steps.stepsOfRun(runId)) {
            for (String line : steps.logText(runId, 1, step.stepKey)) {
                if (line.contains("Streamed in batches") || line.contains("partitions on disk") || line.contains("memory")) {
                    report(String.format("| %s | | log | %s | | | | | | | | | %s |", name, step.stepKey, line.replace('|', '/')));
                }
            }
        }
        for (StepStore.DatasetRow dataset : steps.datasets) {
            String key = steps.storageKeys.get(dataset.runDatasetId);
            report(String.format("| %s | | dataset | %s | %s rows | | | | | | | | %s: %,d bytes on disk |", name, dataset.name, dataset.rowCount,
                key.substring(key.indexOf('/', 9) + 1), sizeOf(root.resolve(key))));
        }
        // The run's files go: the next run starts on an empty store.
        deleteTree(root.resolve("datasets/" + runId));
    }

    static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return -1;
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Collections.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    static long countLines(String file) throws IOException {
        long lines = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) != -1) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        lines++;
                    }
                }
            }
        }
        return lines;
    }

    /** The run's worker callback: every report taken, the run's status moved. */
    static final class Worker implements NotifyService {
        private final JobQueue run;
        volatile String last;

        Worker(JobQueue run) {
            this.run = run;
        }

        @Override
        public ResponseDto changeState(SourceJobQueueDto dto) {
            this.run.setJobStatus(dto.getJobStatus());
            this.last = dto.getJobStatus() + ": " + dto.getJobStatusMessage();
            return new ResponseDto("SUCCESS", "ok");
        }

        @Override public ResponseDto addLogs(SourceJobQueueDto dto) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto changeState(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogs(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages, String key) { throw new UnsupportedOperationException(); }

        @Override public void noteRefusedCallback(Long jobQueueId, JobStatus reportedStatus) { throw new UnsupportedOperationException(); }

        @Override public Optional<ResponseDto> replay(Long jobQueueId, JobStatus s, String request, String key) { throw new UnsupportedOperationException(); }
    }

    // ---------------------------------------------------------------------------------------------------- phases mode

    /** One step's context in this thread: its input, the outputs before it, its kept files on the store. */
    static final class PhaseContext implements StepContext {
        final PipelineDefinition.Step step;
        final Dataset input;
        final Map<String, String> outputs;
        final FileDatasetStore store;
        final long runId;

        PhaseContext(PipelineDefinition.Step step, Dataset input, Map<String, String> outputs, FileDatasetStore store, long runId) {
            this.step = step;
            this.input = input;
            this.outputs = outputs;
            this.store = store;
            this.runId = runId;
        }

        @Override public long tenantId() { return TENANT; }

        @Override public long jobQueueId() { return this.runId; }

        @Override public int attempt() { return 1; }

        @Override public String stepKey() { return this.step.getKey(); }

        @Override public int tryNumber() { return 1; }

        @Override public Map<String, Object> config() { return this.step.effectiveConfig(); }

        @Override public Dataset input() { return this.input; }

        @Override
        public Dataset dataset(String stepKey) throws Exception {
            return this.store.read(this.outputs.get(stepKey));
        }

        @Override
        public void keepFile(String fileName, byte[] content, long rows, List<String> columns) throws Exception {
            this.store.writeFile(DatasetStore.fileKeyOf(this.runId, 1, this.step.getKey(), fileName), content);
        }

        @Override public void log(String message) { }

        @Override public void warn(String message) { }
    }

    @Test
    @EnabledIfSystemProperty(named = "bigdata.bench", matches = "phases")
    void phases() throws Exception {
        String profile = System.getProperty("bigdata.profile", "orders");
        Path root = Paths.get(System.getProperty("bigdata.datasets", System.getProperty("java.io.tmpdir") + "/bigdata-datasets"));
        report(String.format("## %s, phases (this thread), profile %s, -Xmx %.0f MB", label(), profile, Runtime.getRuntime().maxMemory() / MB));
        report("| file | step | phase | rows | cols | time s | allocated MB | peak live (after GC) MB | retained after MB | dataset MB | bytes/cell | notes |");
        report("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (String file : files()) {
            try {
                this.phaseRun(profile, file, root);
            } catch (OutOfMemoryError oom) {
                report("| " + Paths.get(file).getFileName() + " | | | | | | | | | | | OutOfMemoryError |");
            }
            flush("phases.md");
        }
    }

    private void phaseRun(String profile, String file, Path root) throws Exception {
        String name = Paths.get(file).getFileName().toString();
        LocalBuckets buckets = new LocalBuckets();
        StepTasks tasks = new StepTasks(tasks(buckets));
        FileDatasetStore store = new FileDatasetStore(root.toString());
        long runId = RUN_IDS.incrementAndGet();
        Map<String, String> outputs = new LinkedHashMap<>();
        String latest = null;
        HeapWatch watch = new HeapWatch();
        try {
            long base = retained();
            for (PipelineDefinition.Step step : pipeline(profile, file)) {
                String inputKey = step.getInput() != null ? outputs.get(step.getInput()) : latest;
                // load: the engine reads the input back from the store
                Dataset input = Dataset.EMPTY;
                if (inputKey != null) {
                    long r0 = retained();
                    watch.mark();
                    long a0 = allocated();
                    long t0 = System.nanoTime();
                    input = store.read(inputKey);
                    double s = (System.nanoTime() - t0) / 1e9;
                    long a = allocated() - a0;
                    long[] heap = watch.mark();
                    long r1 = retained();
                    this.phase(name, step.getKey(), "load (JSON read back)", input, s, a, heap[1], r1 - base, r1 - r0,
                        String.format("%,d bytes of JSON", sizeOf(root.resolve(inputKey))));
                }
                // run
                long r0 = retained();
                watch.mark();
                long a0 = allocated();
                long t0 = System.nanoTime();
                StepResult result = tasks.find(step.getTask()).get().run(new PhaseContext(step, input, outputs, store, runId));
                double s = (System.nanoTime() - t0) / 1e9;
                long a = allocated() - a0;
                long[] heap = watch.mark();
                Dataset output = result.getOutput();
                long r1 = retained();
                this.phase(name, step.getKey(), "run (" + step.getTask() + ")", output == null ? input : output, s, a, heap[1], r1 - base,
                    r1 - r0, output == null ? "no dataset out; " + result.getRecordsOut() + " records" : "");
                if (output != null) {
                    String key = DatasetStore.keyOf(runId, 1, step.getKey(), "output");
                    watch.mark();
                    a0 = allocated();
                    t0 = System.nanoTime();
                    store.write(key, output);
                    s = (System.nanoTime() - t0) / 1e9;
                    a = allocated() - a0;
                    heap = watch.mark();
                    this.phase(name, step.getKey(), "keep (JSON write)", output, s, a, heap[1], retained() - base, 0,
                        String.format("%,d bytes of JSON", sizeOf(root.resolve(key))));
                    outputs.put(step.getKey(), key);
                    latest = key;
                } else {
                    outputs.put(step.getKey(), inputKey);
                }
                input = null;
                output = null;
                result = null;
            }
        } finally {
            watch.close();
            deleteTree(root.resolve("datasets/" + runId));
        }
    }

    private void phase(String file, String step, String phase, Dataset data, double seconds, long allocated, long peakLive, long retainedAfter,
                       long datasetBytes, String notes) {
        long cells = (long) data.size() * data.getColumns().size();
        report(String.format("| %s | %s | %s | %,d | %d | %.2f | %,.0f | %,.0f | %,.0f | %,.0f | %s | %s |", file, step, phase, data.size(),
            data.getColumns().size(), seconds, allocated / MB, peakLive / MB, retainedAfter / MB, datasetBytes / MB,
            cells == 0 || datasetBytes <= 0 ? "" : String.format("%.0f", (double) datasetBytes / cells), notes));
    }
}
