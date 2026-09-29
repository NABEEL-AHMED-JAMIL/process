package process.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.ai.ModelChoiceStore;
import process.ai.RunAiStep;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.JobOwnership;
import process.pipeline.data.FileFormats;
import process.pipeline.data.RowCollector;
import process.util.BusinessTime;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A run's steps for the console's timeline (MIG-230, for MIG-251's Executions detail): each step's status, times,
 * duration, records in and out, tries, on-error, error and datasets, one attempt at a time, and a step's own log.
 *
 * <b>Every run has a timeline.</b> A run of a legacy pipeline -- every run today -- has no step rows: it is shown as its
 * one legacy step, made from the run itself (its status, start and end, its status line), whose log is the run's own
 * (sourceJob.json/findSourceJobAuditLog). The AI steps the run's pipeline asked for (run_ai_step, MIG-242) are listed
 * with the attempt they belong to, so the worker path's per-step AI handling shows in the same timeline rather than a
 * second one.
 *
 * A run is the caller's when its job is (JobOwnership: the workspace, and for a tenant user the jobs that name them)
 * and the job is not deleted -- the rule, and the words, of the run reads beside it. Anything else is not found.
 */
@Service
public class StepTimelineService {

    static final String RUN_NOT_FOUND = "Run not found with jobQueueId.";
    static final String STEP_NOT_FOUND = "Step not found with stepExecutionId.";
    static final String DATASET_NOT_FOUND = "Dataset not found with runDatasetId.";

    /** A download's answer: a file to stream, or an HTTP status and the envelope that says why not. */
    public static final class Download {
        public final int status;
        public final ResponseDto refusal;
        public final String fileName;
        public final String contentType;
        public final StreamingResponseBody body;

        private Download(int status, ResponseDto refusal, String fileName, String contentType, StreamingResponseBody body) {
            this.status = status;
            this.refusal = refusal;
            this.fileName = fileName;
            this.contentType = contentType;
            this.body = body;
        }

        static Download refused(int status, String message) {
            return new Download(status, new ResponseDto(ERROR, message), null, null, null);
        }

        static Download file(String fileName, String format, StreamingResponseBody body) {
            return new Download(200, null, fileName, FileFormats.contentType(format), body);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final StepStore steps;
    private final ModelChoiceStore aiSteps;
    private final DatasetStore datasets;

    public StepTimelineService(JobQueueRepository runs, SourceJobRepository jobs, StepStore steps, ModelChoiceStore aiSteps,
                               DatasetStore datasets) {
        this.runs = runs;
        this.jobs = jobs;
        this.steps = steps;
        this.aiSteps = aiSteps;
        this.datasets = datasets;
    }

    /** The timeline of one attempt of a run: the latest when none is named. */
    @Transactional(readOnly = true)
    public ResponseDto timeline(Long jobQueueId, Integer attempt) {
        Optional<JobQueue> run = this.owned(jobQueueId);
        if (!run.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        List<StepStore.StepRow> rows = this.steps.stepsOfRun(run.get().getJobQueueId());
        List<RunAiStep> ai = this.aiSteps.stepsOfRun(run.get().getJobQueueId());
        TreeSet<Integer> attempts = new TreeSet<>();
        rows.forEach(row -> attempts.add(row.attempt));
        ai.forEach(step -> attempts.add(step.attempt == null ? 1 : step.attempt));
        attempts.add(Math.max(1, run.get().getAttempt()));
        int shown = attempt != null ? attempt : attempts.last();
        if (!attempts.contains(shown)) {
            return new ResponseDto(ERROR, String.format("This run has no attempt %d; it has %s.", shown, attempts));
        }

        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("jobQueueId", run.get().getJobQueueId());
        timeline.put("jobId", run.get().getJobId());
        timeline.put("runStatus", run.get().getJobStatus());
        timeline.put("attempt", shown);
        timeline.put("attempts", new ArrayList<>(attempts));
        List<StepStore.StepRow> ofAttempt = rows.stream().filter(row -> row.attempt == shown).collect(Collectors.toList());
        boolean legacy = rows.isEmpty();
        timeline.put("legacy", legacy);
        if (legacy) {
            timeline.put("steps", Collections.singletonList(this.legacyStep(run.get(), shown)));
        } else {
            Map<Long, List<StepStore.DatasetRow>> datasets = this.steps.datasetsOfRun(run.get().getJobQueueId()).stream()
                .collect(Collectors.groupingBy(dataset -> dataset.stepExecutionId));
            timeline.put("pipelineDefinitionId", ofAttempt.isEmpty() ? null : ofAttempt.get(0).pipelineDefinitionId);
            timeline.put("steps", ofAttempt.stream().map(row -> step(row, datasets.getOrDefault(row.stepExecutionId,
                Collections.emptyList()))).collect(Collectors.toList()));
        }
        timeline.put("aiSteps", ai.stream().filter(step -> (step.attempt == null ? 1 : step.attempt) == shown).collect(Collectors.toList()));
        return new ResponseDto(SUCCESS, String.format("%d step(s) in attempt %d.", ((List<?>) timeline.get("steps")).size(), shown),
            timeline);
    }

    /** One step's own log, in order. */
    @Transactional(readOnly = true)
    public ResponseDto log(Long stepExecutionId) {
        Optional<StepStore.StepRow> step = stepExecutionId == null ? Optional.empty() : this.steps.stepById(stepExecutionId);
        if (!step.isPresent() || !this.owned(step.get().jobQueueId).isPresent()) {
            return new ResponseDto(ERROR, STEP_NOT_FOUND);
        }
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("stepExecutionId", step.get().stepExecutionId);
        log.put("jobQueueId", step.get().jobQueueId);
        log.put("stepKey", step.get().stepKey);
        log.put("attempt", step.get().attempt);
        log.put("lines", this.steps.logOf(step.get().stepExecutionId));
        return new ResponseDto(SUCCESS, String.format("%d line(s).", ((List<?>) log.get("lines")).size()), log);
    }

    /**
     * A run dataset as a file (Wave 4): a step's output as CSV, JSON or JSON Lines (CSV when no format is named), or a
     * file Save File kept, as it was written (or converted, when another format is named). The caller's when its run
     * is (the timeline's rule); anything else, another workspace's included, is not found (404). Past its expiresAt it
     * is gone (410) -- after the sweep too, for a kept file, which the run's manifest still names.
     */
    @Transactional(readOnly = true)
    public Download download(Long runDatasetId, String format) {
        String wanted = format == null || format.trim().isEmpty() ? null : format.trim().toLowerCase(Locale.ROOT);
        if (wanted != null && !FileFormats.WRITABLE.contains(wanted)) {
            return Download.refused(400, String.format("A dataset downloads as csv, json or jsonl; got '%s'.", format));
        }
        Optional<StepStore.DatasetFile> found = runDatasetId == null ? Optional.empty() : this.steps.datasetById(runDatasetId);
        if (!found.isPresent()) {
            Optional<StepStore.OutputRow> swept = runDatasetId == null ? Optional.empty() : this.steps.outputOfDataset(runDatasetId);
            if (swept.isPresent() && this.owned(swept.get().jobQueueId).isPresent()) {
                return Download.refused(410, expired(swept.get().expiresAt));
            }
            return Download.refused(404, DATASET_NOT_FOUND);
        }
        StepStore.DatasetFile dataset = found.get();
        if (!this.owned(dataset.jobQueueId).isPresent()) {
            return Download.refused(404, DATASET_NOT_FOUND);
        }
        if (dataset.expiresAt != null && !dataset.expiresAt.isAfter(Instant.now())) {
            return Download.refused(410, expired(dataset.expiresAt));
        }
        try {
            if (dataset.storageKey.contains("/files/")) {
                return this.keptFile(dataset, wanted);
            }
            Dataset rows = this.datasets.read(dataset.storageKey);
            String as = wanted == null ? "csv" : wanted;
            String name = String.format("run-%d-attempt-%d-%s-%s.%s", dataset.jobQueueId, dataset.attempt, dataset.stepKey, dataset.name, as);
            return Download.file(name, as, out -> FileFormats.writeTo(rows, as, out));
        } catch (Exception ex) {
            return Download.refused(410, "This dataset's content is no longer available; run the job again to make it anew.");
        }
    }

    /**
     * A run's result manifest (Wave 4): the files its steps wrote, every attempt unless one is named -- a kept file with
     * the runDatasetId that downloads it (sourceJob.json/runDataset) and when it expires, an upload with the bucket alias
     * and key storage-service's browse endpoints download. Never a storage key.
     */
    @Transactional(readOnly = true)
    public ResponseDto outputs(Long jobQueueId, Integer attempt) {
        Optional<JobQueue> run = this.owned(jobQueueId);
        if (!run.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        Instant now = Instant.now();
        List<Map<String, Object>> outputs = this.steps.outputsOfRun(run.get().getJobQueueId()).stream()
            .filter(row -> attempt == null || row.attempt == attempt)
            .map(row -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("runOutputId", row.runOutputId);
                out.put("stepExecutionId", row.stepExecutionId);
                out.put("attempt", row.attempt);
                out.put("stepIndex", row.stepIndex);
                out.put("stepKey", row.stepKey);
                out.put("task", row.taskCode);
                out.put("kind", row.kind);
                out.put("name", row.name);
                out.put("format", row.format);
                out.put("rowCount", row.rowCount);
                out.put("byteCount", row.byteCount);
                out.put("recordedAt", row.recordedAt);
                if (RunOutput.FILE.equals(row.kind)) {
                    out.put("runDatasetId", row.runDatasetId);
                    out.put("expiresAt", row.expiresAt == null ? null : BusinessTime.wallClockOf(row.expiresAt));
                    out.put("expired", row.expiresAt != null && !row.expiresAt.isAfter(now));
                } else {
                    out.put("bucket", row.bucketAlias);
                    out.put("key", row.objectKey);
                }
                return out;
            }).collect(Collectors.toList());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("jobQueueId", run.get().getJobQueueId());
        manifest.put("jobId", run.get().getJobId());
        manifest.put("attempt", attempt);
        manifest.put("outputs", outputs);
        return new ResponseDto(SUCCESS, String.format("%d file(s).", outputs.size()), manifest);
    }

    /** A file Save File kept: its own bytes in its own format, or its rows in the one asked for. */
    private Download keptFile(StepStore.DatasetFile dataset, String wanted) throws Exception {
        byte[] content = this.datasets.readFile(dataset.storageKey);
        String own = this.steps.outputOfDataset(dataset.runDatasetId).map(output -> output.format)
            .orElseGet(() -> Optional.ofNullable(FileFormats.byExtension(dataset.name)).orElse("csv"));
        if (wanted == null || wanted.equals(own)) {
            return Download.file(dataset.name, own, out -> out.write(content));
        }
        RowCollector collector = new RowCollector("The file", null);
        FileFormats.read(content, own, new FileFormats.ReadOptions(), collector);
        Dataset rows = collector.toDataset(parseColumns(dataset.columns));
        int dot = dataset.name.lastIndexOf('.');
        String name = (dot > 0 ? dataset.name.substring(0, dot) : dataset.name) + "." + wanted;
        return Download.file(name, wanted, out -> FileFormats.writeTo(rows, wanted, out));
    }

    private static String expired(Instant at) {
        return String.format("This dataset expired%s and can no longer be downloaded: a run's datasets are kept for its pipeline's "
            + "datasetRetentionHours. Run the job again to make it anew.", at == null ? ""
            : " at " + BusinessTime.wallClockOf(at).withNano(0).toString().replace('T', ' ') + " (Chicago)");
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseColumns(String json) {
        Object parsed = parseList(json);
        List<String> columns = new ArrayList<>();
        for (Object column : (List<Object>) parsed) {
            columns.add(String.valueOf(column));
        }
        return columns;
    }

    private Optional<JobQueue> owned(Long jobQueueId) {
        if (jobQueueId == null) {
            return Optional.empty();
        }
        Optional<JobQueue> run = this.runs.findById(jobQueueId);
        Optional<SourceJob> job = run.flatMap(r -> r.getJobId() == null ? Optional.empty() : this.jobs.findById(r.getJobId()));
        if (!run.isPresent() || !job.isPresent() || !JobOwnership.isVisibleToCaller(job.get())
            || Status.Delete.equals(job.get().getJobStatus()) || !Objects.equals(run.get().getTenantId(), job.get().getTenantId())) {
            return Optional.empty();
        }
        return run;
    }

    /** A legacy run as its one step: the run's own status, times and status line; its log is the run's. */
    private Map<String, Object> legacyStep(JobQueue run, int attempt) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepExecutionId", null);
        step.put("index", 0);
        step.put("key", PipelineDefinition.LEGACY_KEY);
        step.put("task", PipelineDefinition.LEGACY_TASK);
        step.put("status", run.getJobStatus() == null ? null : run.getJobStatus().name());
        step.put("startedAt", run.getStartTime());
        step.put("endedAt", run.getEndTime());
        step.put("durationMs", run.getStartTime() == null || run.getEndTime() == null ? null
            : Duration.between(BusinessTime.instantOf(run.getStartTime()), BusinessTime.instantOf(run.getEndTime())).toMillis());
        step.put("recordsIn", null);
        step.put("recordsOut", null);
        step.put("tries", attempt);
        step.put("onError", null);
        step.put("statusMessage", run.getJobStatusMessage());
        step.put("error", null);
        step.put("datasets", Collections.emptyList());
        step.put("log", "run");
        return step;
    }

    private static Map<String, Object> step(StepStore.StepRow row, List<StepStore.DatasetRow> datasets) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepExecutionId", row.stepExecutionId);
        step.put("index", row.stepIndex);
        step.put("key", row.stepKey);
        step.put("task", row.taskCode);
        step.put("status", row.status);
        step.put("startedAt", row.startedAt);
        step.put("endedAt", row.endedAt);
        step.put("durationMs", row.durationMs);
        step.put("recordsIn", row.recordsIn);
        step.put("recordsOut", row.recordsOut);
        step.put("tries", row.tries);
        step.put("onError", row.onError);
        step.put("statusMessage", row.statusMessage);
        step.put("error", parse(row.error));
        step.put("datasets", datasets.stream().map(dataset -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runDatasetId", dataset.runDatasetId);
            out.put("name", dataset.name);
            out.put("rowCount", dataset.rowCount);
            out.put("columns", parseList(dataset.columns));
            out.put("expiresAt", dataset.expiresAt);
            return out;
        }).collect(Collectors.toList()));
        step.put("log", "step");
        return step;
    }

    private static Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return json;
        }
    }

    private static Object parseList(String json) {
        if (json == null) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }
}
