package process.pipeline;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** {@link StepStore} in memory, for the engine's unit tests: the rows as the engine left them. */
public class InMemoryStepStore implements StepStore {

    public final Map<Long, StepRow> rows = new LinkedHashMap<>();
    public final Map<Long, List<LogLine>> logs = new LinkedHashMap<>();
    public final List<DatasetRow> datasets = new ArrayList<>();
    public final Map<Long, String> storageKeys = new LinkedHashMap<>();
    public final List<Long> claimed = new ArrayList<>();
    public boolean claimable = true;
    private final AtomicLong ids = new AtomicLong(1000);

    @Override
    public synchronized boolean claimForEngine(long jobQueueId, String correlationId) {
        this.claimed.add(jobQueueId);
        return this.claimable;
    }

    @Override
    public synchronized Optional<Long> pinnedDefinition(long jobQueueId) {
        // The latest attempt's (MIG-345), as JdbcStepStore orders it.
        return this.rows.values().stream().filter(r -> r.jobQueueId == jobQueueId && r.pipelineDefinitionId != null)
            .sorted((a, b) -> a.attempt != b.attempt ? Integer.compare(b.attempt, a.attempt) : Integer.compare(a.stepIndex, b.stepIndex))
            .map(r -> r.pipelineDefinitionId).findFirst();
    }

    /** MIG-345: run_input_file in memory, by step execution. */
    public final Map<Long, List<InputDigests.Read>> inputs = new LinkedHashMap<>();
    /** MIG-345: the datasets the sweep removed (retention_log), by run_dataset id. */
    public final Set<Long> swept = new HashSet<>();

    @Override
    public synchronized void reused(long id, long fromStepExecutionId, Long recordsIn, Long recordsOut, String message) {
        StepRow row = this.rows.get(id);
        row.status = "Completed";
        row.reusedFrom = fromStepExecutionId;
        row.recordsIn = recordsIn;
        row.recordsOut = recordsOut;
        row.startedAt = null;
        row.endedAt = null;
        row.tries = 0;
        row.statusMessage = message;
    }

    @Override
    public synchronized void inputRead(long id, InputDigests.Read read) {
        List<InputDigests.Read> reads = this.inputs.computeIfAbsent(id, k -> new ArrayList<>());
        reads.removeIf(r -> r.bucket.equals(read.bucket) && r.key.equals(read.key));
        reads.add(read);
    }

    @Override
    public synchronized Map<Long, List<InputDigests.Read>> inputsOf(Collection<Long> ids) {
        Map<Long, List<InputDigests.Read>> found = new LinkedHashMap<>();
        for (Long id : ids) {
            if (this.inputs.containsKey(id)) {
                found.put(id, new ArrayList<>(this.inputs.get(id)));
            }
        }
        return found;
    }

    @Override
    public synchronized Map<Long, KeptOutput> keptOutputsOf(Collection<Long> ids) {
        Map<Long, KeptOutput> kept = new LinkedHashMap<>();
        for (DatasetRow dataset : this.datasets) {
            if ("output".equals(dataset.name) && ids.contains(dataset.stepExecutionId)) {
                kept.put(dataset.stepExecutionId, new KeptOutput(dataset.stepExecutionId, this.storageKeys.get(dataset.runDatasetId),
                    dataset.rowCount, this.datasetExpiry.get(dataset.runDatasetId), this.swept.contains(dataset.runDatasetId)));
            }
        }
        return kept;
    }

    @Override
    public synchronized List<Long> plan(long jobQueueId, int attempt, long pipelineDefinitionId, List<Planned> steps) {
        List<Long> planned = new ArrayList<>();
        for (Planned step : steps) {
            Optional<StepRow> existing = this.rows.values().stream()
                .filter(r -> r.jobQueueId == jobQueueId && r.attempt == attempt && r.stepIndex == step.index).findFirst();
            if (existing.isPresent()) {
                planned.add(existing.get().stepExecutionId);
                continue;
            }
            StepRow row = new StepRow();
            row.stepExecutionId = this.ids.incrementAndGet();
            row.jobQueueId = jobQueueId;
            row.attempt = attempt;
            row.stepIndex = step.index;
            row.stepKey = step.key;
            row.taskCode = step.task;
            row.onError = step.onError;
            row.status = "Queue";
            row.pipelineDefinitionId = pipelineDefinitionId;
            this.rows.put(row.stepExecutionId, row);
            planned.add(row.stepExecutionId);
        }
        return planned;
    }

    @Override
    public synchronized void started(long id, Long recordsIn) {
        StepRow row = this.rows.get(id);
        row.status = "Running";
        row.recordsIn = recordsIn;
        row.startedAt = LocalDateTime.now();
    }

    @Override
    public synchronized void tried(long id, int tries) {
        this.rows.get(id).tries = tries;
    }

    @Override
    public synchronized void ended(long id, String status, Long recordsOut, String errorJson, String message) {
        StepRow row = this.rows.get(id);
        row.status = status;
        row.recordsOut = recordsOut;
        row.error = errorJson;
        row.statusMessage = message;
        row.endedAt = LocalDateTime.now();
    }

    @Override
    public synchronized void notRun(long id, String status, String message) {
        StepRow row = this.rows.get(id);
        row.status = status;
        row.statusMessage = message;
    }

    @Override
    public synchronized void log(long id, int lineNo, String level, String message) {
        LogLine line = new LogLine();
        line.lineNo = lineNo;
        line.level = level;
        line.message = message;
        this.logs.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
    }

    @Override
    public synchronized long dataset(long id, String name, String storageKey, long rows, String columnsJson, Instant expiresAt) {
        DatasetRow row = new DatasetRow();
        row.runDatasetId = this.ids.incrementAndGet();
        row.stepExecutionId = id;
        row.name = name;
        row.rowCount = rows;
        row.columns = columnsJson;
        this.datasets.add(row);
        this.storageKeys.put(row.runDatasetId, storageKey);
        this.datasetExpiry.put(row.runDatasetId, expiresAt);
        return row.runDatasetId;
    }

    @Override
    public synchronized List<StepRow> stepsOfRun(long jobQueueId) {
        return this.rows.values().stream().filter(r -> r.jobQueueId == jobQueueId).collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<StepRow> stepById(long id) {
        return Optional.ofNullable(this.rows.get(id));
    }

    @Override
    public synchronized List<LogLine> logOf(long id) {
        return new ArrayList<>(this.logs.getOrDefault(id, new ArrayList<>()));
    }

    @Override
    public synchronized List<DatasetRow> datasetsOfRun(long jobQueueId) {
        return this.datasets.stream().filter(d -> this.rows.get(d.stepExecutionId).jobQueueId == jobQueueId).collect(Collectors.toList());
    }

    /** run_output in memory: one per step execution, a later record replacing it. */
    public final Map<String, OutputRow> outputs = new LinkedHashMap<>();
    public final Map<Long, Instant> datasetExpiry = new LinkedHashMap<>();

    @Override
    public synchronized Optional<DatasetFile> datasetById(long runDatasetId) {
        return this.datasets.stream().filter(d -> d.runDatasetId == runDatasetId).findFirst().map(d -> {
            StepRow step = this.rows.get(d.stepExecutionId);
            DatasetFile file = new DatasetFile();
            file.runDatasetId = d.runDatasetId;
            file.stepExecutionId = d.stepExecutionId;
            file.jobQueueId = step.jobQueueId;
            file.attempt = step.attempt;
            file.stepKey = step.stepKey;
            file.name = d.name;
            file.storageKey = this.storageKeys.get(d.runDatasetId);
            file.columns = d.columns;
            file.expiresAt = this.datasetExpiry.get(d.runDatasetId);
            return file;
        });
    }

    @Override
    public synchronized void output(long stepExecutionId, RunOutput output, Long runDatasetId, Instant expiresAt) {
        StepRow step = this.rows.get(stepExecutionId);
        OutputRow row = new OutputRow();
        String key = stepExecutionId + "/" + output.getName();
        OutputRow before = this.outputs.get(key);
        row.runOutputId = before != null ? before.runOutputId : this.ids.incrementAndGet();
        row.stepExecutionId = stepExecutionId;
        row.jobQueueId = step.jobQueueId;
        row.attempt = step.attempt;
        row.stepIndex = step.stepIndex;
        row.stepKey = step.stepKey;
        row.taskCode = step.taskCode;
        row.kind = output.getKind();
        row.name = output.getName();
        row.format = output.getFormat();
        row.rowCount = output.getRows();
        row.byteCount = output.getBytes();
        row.runDatasetId = runDatasetId;
        row.bucketAlias = output.getBucket();
        row.objectKey = output.getKey();
        row.expiresAt = expiresAt;
        row.recordedAt = LocalDateTime.now();
        row.sha256 = output.getSha256();
        row.pageCount = output.getPages();
        this.outputs.put(key, row);
    }

    @Override
    public synchronized List<OutputRow> outputsOfRun(long jobQueueId) {
        return this.outputs.values().stream().filter(o -> o.jobQueueId == jobQueueId).collect(Collectors.toList());
    }

    @Override
    public synchronized List<OutputRow> bucketWritesOf(String bucketAlias, String objectKey, int limit) {
        // Newest first: the later recorded row (the higher id on a tie, as JdbcStepStore orders them).
        return this.outputs.values().stream()
            .filter(o -> RunOutput.BUCKET.equals(o.kind) && bucketAlias.equals(o.bucketAlias) && objectKey.equals(o.objectKey))
            .sorted((a, b) -> b.recordedAt.equals(a.recordedAt) ? Long.compare(b.runOutputId, a.runOutputId) : b.recordedAt.compareTo(a.recordedAt))
            .limit(Math.max(1, limit)).collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<OutputRow> outputOfDataset(long runDatasetId) {
        return this.outputs.values().stream().filter(o -> o.runDatasetId != null && o.runDatasetId == runDatasetId).findFirst();
    }

    /** The rows of one attempt, by step key: status, as the timeline would show them. */
    public synchronized Map<String, String> statuses(long jobQueueId, int attempt) {
        Map<String, String> statuses = new LinkedHashMap<>();
        this.rows.values().stream().filter(r -> r.jobQueueId == jobQueueId && r.attempt == attempt)
            .forEach(r -> statuses.put(r.stepKey, r.status));
        return statuses;
    }

    public synchronized StepRow row(long jobQueueId, int attempt, String key) {
        return this.rows.values().stream().filter(r -> r.jobQueueId == jobQueueId && r.attempt == attempt && key.equals(r.stepKey))
            .findFirst().orElseThrow(() -> new AssertionError("no step " + key));
    }

    public synchronized List<String> logText(long jobQueueId, int attempt, String key) {
        return this.logOf(this.row(jobQueueId, attempt, key).stepExecutionId).stream().map(l -> l.level + " " + l.message)
            .collect(Collectors.toList());
    }
}
