package process.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.ApiTimes;
import org.barco.platform.api.Problem;
import org.barco.platform.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.engine.OneRunInFlight;
import process.engine.ProducerBulkEngine;
import process.forms.StorageFormInbox;
import process.forms.FormInbox;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.BucketStore;
import process.storage.remote.StorageServiceClient;
import process.util.BusinessTime;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starting a pipeline's run from the customer API (MIG-332), the way a form submission starts its job
 * (FormSubmissionService.start): the record -- and the files it names -- written as one JSON intake file into the
 * workspace's inbox bucket, the run queued with that file as its input (a step pipeline's Read JSON with no bucket and key
 * reads it), and the start recorded in api_intake. Run now's rules apply: the job active, the workspace not paused, no
 * run of it in flight (one in flight per job, V83).
 *
 * The intake file is one JSON object: the record's own fields, then the start's, each under a name beginning with "_"
 * so a record's own field never meets one: _reference, _client_id, _received_at, _pipeline_id, _event_id, _event_type,
 * _files (each named file: id, name, bucket, key, sha256, bytes, contentType), _file_ids, and the first file's
 * _file_bucket and _file_key, for a step that reads a file by column.
 */
@Component
public class RunIntake {

    private static final Logger logger = LoggerFactory.getLogger(RunIntake.class);
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a start needs besides the pipeline. */
    public static final class Start {
        public JsonNode record;
        public List<Map<String, Object>> files = new ArrayList<>();
        public String reference;
        public String clientId;
        public Long eventId;
        public String eventType;
        /** For the run's audit line: "a run started", "event order.received". */
        public String origin;
    }

    /** A start's outcome: the run as the API shows it, or why it did not start (a problem without its instance). */
    public static final class Outcome {
        public final Map<String, Object> run;
        public final Problem refusal;

        Outcome(Map<String, Object> run, Problem refusal) {
            this.run = run;
            this.refusal = refusal;
        }
    }

    private final TransactionServiceImpl jobs;
    private final ProducerBulkEngine engine;
    private final BucketStore buckets;
    private final StorageServiceClient storage;
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;

    public RunIntake(TransactionServiceImpl jobs, ProducerBulkEngine engine, BucketStore buckets, StorageServiceClient storage,
        JdbcTemplate sql, PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.engine = engine;
        this.buckets = buckets;
        this.storage = storage;
        this.sql = sql;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Starts the pipeline's run with this intake, or says why not. Nothing is written when it refuses before the file. */
    public Outcome start(PipelineCatalogue.Entry pipeline, Start start) {
        long tenantId = pipeline.tenantId;
        Optional<SourceJob> job = this.jobs.findByJobId(pipeline.jobId).filter(j -> j.getTenantId() != null && j.getTenantId() == tenantId);
        if (!job.isPresent()) {
            return refused(Problem.of(404, "No such pipeline."));
        }
        if (!"Active".equals(String.valueOf(job.get().getJobStatus()))) {
            return refused(Problem.of(409, "The pipeline is not active.").kind("pipeline-inactive"));
        }
        Optional<String> paused = this.engine.workspacePause(tenantId);
        if (paused.isPresent()) {
            return refused(Problem.of(409, "This workspace is " + paused.get() + ", so its runs are paused.").kind("workspace-paused"));
        }
        if (job.get().getJobRunningStatus() != null && job.get().getJobRunningStatus().isInFlight()) {
            return refused(busy());
        }
        Optional<String> unavailable = this.buckets.unavailable();
        if (unavailable.isPresent()) {
            logger.warn("A run of job {} was not started through the API: {}", pipeline.jobId, unavailable.get());
            return refused(Problem.of(503, "Runs cannot be started right now. Try again in a moment."));
        }
        FormInbox.Location inbox;
        try {
            inbox = StorageFormInbox.of(this.storage.inboxOf(tenantId));
        } catch (RuntimeException unreachable) {
            logger.warn("The inbox of workspace {} could not be read for an API start: {}", tenantId, unreachable.getMessage());
            return refused(Problem.of(503, "Runs cannot be started right now. Try again in a moment."));
        }
        if (inbox.alias == null) {
            return refused(Problem.of(409, "This workspace has no inbox for the API's intake files yet. An administrator chooses one "
                + "under Documents > Inbox.").kind("workspace-not-ready"));
        }
        OffsetDateTime received = OffsetDateTime.now(BusinessTime.ZONE);
        String key = String.format("intake/api/runs/%s/job-%d-%s.json", received.format(FOLDER), pipeline.jobId, CorrelationId.generate());
        try {
            this.buckets.upload(tenantId, inbox.alias, key, fileOf(pipeline, start, received), "application/json");
        } catch (Exception unwritten) {
            logger.warn("The intake of an API start of job {} could not be written to {}/{}: {}", pipeline.jobId, inbox.alias, key,
                unwritten.getMessage());
            return refused(Problem.of(503, "Runs cannot be started right now. Try again in a moment."));
        }
        long runId;
        try {
            runId = this.transactions.execute(status -> {
                JobQueue run = this.engine.addApiJobInQueue(job.get(), inbox.alias, key, start.clientId, start.origin);
                this.sql.update("INSERT INTO api_intake (tenant_id, job_id, job_queue_id, client_id, reference, event_id, input_bucket, "
                    + "input_key, file_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", tenantId, pipeline.jobId, run.getJobQueueId(), start.clientId,
                    start.reference, start.eventId, inbox.alias, key, fileIds(start.files));
                return run.getJobQueueId();
            });
        } catch (RuntimeException failed) {
            if (OneRunInFlight.isViolation(failed)) {
                return refused(busy());
            }
            throw failed;
        }
        return new Outcome(this.run(tenantId, runId, pipeline), null);
    }

    /** The Run schema for a run started through the API, read back as stored; times in UTC. */
    Map<String, Object> run(long tenantId, long runId, PipelineCatalogue.Entry pipeline) {
        Map<String, Object> row = this.sql.queryForMap("SELECT q.job_queue_id, q.job_id, q.job_status, q.job_status_message, q.date_created, "
            + "q.end_time, i.reference FROM job_queue q LEFT JOIN api_intake i ON i.job_queue_id = q.job_queue_id "
            + "WHERE q.tenant_id = ? AND q.job_queue_id = ?", tenantId, runId);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", String.valueOf(row.get("job_queue_id")));
        run.put("pipelineId", String.valueOf(row.get("job_id")));
        String status = row.get("job_status") == null ? null : row.get("job_status").toString();
        run.put("status", statusOf(status));
        run.put("reference", row.get("reference"));
        run.put("createdAt", ApiTimes.utc(row.get("date_created")));
        run.put("startedAt", null);
        run.put("endedAt", ApiTimes.utc(row.get("end_time")));
        run.put("message", null);
        run.put("review", pipeline.review().isEmpty() ? "not_required" : "pending");
        return run;
    }

    /** The API's word for a run's status: Queue and Start are queued, Skip and Missed skipped. */
    static String statusOf(String status) {
        if (status == null) {
            return "queued";
        }
        switch (JobStatus.valueOf(status)) {
            case Running:
                return "running";
            case Completed:
                return "completed";
            case Failed:
                return "failed";
            case Skip:
            case Missed:
                return "skipped";
            case Interrupt:
                return "interrupted";
            default:
                return "queued";
        }
    }

    static Problem busy() {
        return Problem.of(409, "A run of this pipeline is still in flight; start another once it has finished.").kind("run-in-flight");
    }

    private static Outcome refused(Problem problem) {
        return new Outcome(null, problem);
    }

    /** The intake file: the record's fields, then the start's under "_" names. */
    static byte[] fileOf(PipelineCatalogue.Entry pipeline, Start start, OffsetDateTime received) {
        Map<String, Object> file = new LinkedHashMap<>();
        if (start.record != null && start.record.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = start.record.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                file.put(field.getKey(), field.getValue());
            }
        }
        file.put("_reference", start.reference);
        file.put("_client_id", start.clientId);
        file.put("_received_at", ApiTimes.utc(received));
        file.put("_pipeline_id", String.valueOf(pipeline.jobId));
        if (start.eventId != null) {
            file.put("_event_id", String.valueOf(start.eventId));
            file.put("_event_type", start.eventType);
        }
        file.put("_files", start.files);
        file.put("_file_ids", fileIds(start.files));
        if (!start.files.isEmpty()) {
            file.put("_file_bucket", start.files.get(0).get("bucket"));
            file.put("_file_key", start.files.get(0).get("key"));
        }
        try {
            return JSON.writeValueAsString(file).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("The intake could not be written as JSON.", unwritable);
        }
    }

    private static String fileIds(List<Map<String, Object>> files) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> file : files) {
            ids.add(String.valueOf(file.get("id")));
        }
        return ids.isEmpty() ? null : String.join(",", ids);
    }
}
