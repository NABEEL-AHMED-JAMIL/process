package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.util.LocalDateTimeAdapter;

import javax.persistence.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@Entity

@Table(name = "job_queue", indexes = {
    @Index(name = "idx_job_queue_job_id", columnList = "job_id")
})
/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobQueue {

    @GenericGenerator(
        name = "jobQueueSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "job_queue_source_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "job_queue_id")
    @GeneratedValue(generator = "jobQueueSequenceGenerator")
    private Long jobQueueId;

    @Column(name = "start_time",
        columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private LocalDateTime startTime;

    @Column(name = "end_time",
        columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private LocalDateTime endTime;

    @Column(name = "skip_time",
        columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private LocalDateTime skipTime;

    @Column(name = "job_status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private JobStatus jobStatus;

    @Column(name = "job_id",
        nullable = false)
    private Long jobId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", insertable = false, updatable = false)
    private SourceJob sourceJob;

    @Column(name = "job_status_message", columnDefinition = "TEXT")
    private String jobStatusMessage;

    @Column(name = "bucket")
    private String bucket;

    @Column(name = "output_folder")
    private String outputFolder;

    @Column(name = "skip_manual")
    private Boolean skipManual;

    @Column(name = "run_manual")
    private Boolean runManual;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    @Column(name = "job_send")
    private boolean jobSend;

    /**
     * Which attempt this run is, starting at 1.
     *
     * A retry re-uses this row rather than inserting a new one -- see V38__job_retry.sql for why --
     * so this is how anyone can tell that a run which finally succeeded took three goes to do it.
     */
    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    /**
     * When a run awaiting retry becomes eligible for dispatch; null for every ordinary run.
     *
     * The dispatcher's pick-up query will not take a Queue row whose value here is still in the
     * future, which is the whole of the backoff mechanism. Compared against the application clock
     * passed in as the query's cutoff. (It was never the database's while the column held Chicago
     * wall-clock and now() was UTC; since V100 both are instants, but the cutoff stays a parameter.)
     */
    @Column(name = "next_attempt_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private LocalDateTime nextAttemptAt;

    /**
     * The run's callback token, as the server keeps it: a hash, the attempt it was minted for,
     * and when it stops being accepted. The token itself goes to the worker in the run's message
     * and is never stored. Null until dispatch, and again once the run ends. See V42.
     */
    @Column(name = "callback_token_hash", length = 64)
    private String callbackTokenHash;

    @Column(name = "callback_token_attempt")
    private Integer callbackTokenAttempt;

    @Column(name = "callback_token_expires_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private LocalDateTime callbackTokenExpiresAt;

    /**
     * A report from this run's own worker that had to be refused because its token had expired, and
     * what it said (V81, MIG-63). The refusal stands -- nothing here changes the run's status -- but it
     * is proof the worker is no longer able to report, so the stall sweep closes the run on its next
     * pass instead of leaving it in flight, blocking its job, until the six-hour rule catches it.
     */
    @Column(name = "refused_callback_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private LocalDateTime refusedCallbackAt;

    @Column(name = "refused_callback_status", length = 16)
    private String refusedCallbackStatus;

    /**
     * The id the run's dispatch logged under, stamped in the same write as the callback token hash
     * (V82, MIG-95). A worker that echoes no X-Correlation-Id on its callbacks is logged under this one,
     * so one search finds the dispatch and every callback it caused. Kept across a retry: the retry is
     * the same piece of work. Never an input to authentication.
     */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /**
     * When the pre-dispatch phase finished with this run, and the document it prepared: the task's
     * payload with every server AI step's answer written in (V86, MIG-134). The dispatcher takes only
     * prepared runs and sends exactly this. A retry clears both, so the next attempt is prepared afresh
     * from the task as it then is -- the AI service reuses any answer it already recorded for the run.
     */
    @Column(name = "prepared_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private LocalDateTime preparedAt;

    @Column(name = "dispatch_payload", columnDefinition = "TEXT")
    private String dispatchPayload;

    @Column(name = "status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;
    public JobQueue() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
        if (this.status == null) {
            this.status = Status.Active;
        }
    }

    public Long getJobQueueId() {
        return jobQueueId;
    }

    public void setJobQueueId(Long jobQueueId) {
        this.jobQueueId = jobQueueId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getSkipTime() {
        return skipTime;
    }

    public void setSkipTime(LocalDateTime skipTime) {
        this.skipTime = skipTime;
    }

    public JobStatus getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(JobStatus jobStatus) {
        this.jobStatus = jobStatus;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public SourceJob getSourceJob() {
        return sourceJob;
    }

    public String getJobStatusMessage() {
        return jobStatusMessage;
    }

    public void setJobStatusMessage(String jobStatusMessage) {
        this.jobStatusMessage = jobStatusMessage;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getOutputFolder() {
        return outputFolder;
    }

    public void setOutputFolder(String outputFolder) {
        this.outputFolder = outputFolder;
    }

    public Boolean getSkipManual() {
        return skipManual;
    }

    public void setSkipManual(Boolean skipManual) {
        this.skipManual = skipManual;
    }

    public Boolean getRunManual() {
        return runManual;
    }

    public void setRunManual(Boolean runManual) {
        this.runManual = runManual;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public boolean isJobSend() {
        return jobSend;
    }

    public void setJobSend(boolean jobSend) {
        this.jobSend = jobSend;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        Gson gson = new GsonBuilder()
        .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
        .create();
        return gson.toJson(this);
    }


    public String getCallbackTokenHash() { return callbackTokenHash; }
    public void setCallbackTokenHash(String callbackTokenHash) { this.callbackTokenHash = callbackTokenHash; }
    public Integer getCallbackTokenAttempt() { return callbackTokenAttempt; }
    public void setCallbackTokenAttempt(Integer callbackTokenAttempt) { this.callbackTokenAttempt = callbackTokenAttempt; }
    public LocalDateTime getCallbackTokenExpiresAt() { return callbackTokenExpiresAt; }
    public void setCallbackTokenExpiresAt(LocalDateTime callbackTokenExpiresAt) { this.callbackTokenExpiresAt = callbackTokenExpiresAt; }
    public LocalDateTime getRefusedCallbackAt() { return refusedCallbackAt; }
    public void setRefusedCallbackAt(LocalDateTime refusedCallbackAt) { this.refusedCallbackAt = refusedCallbackAt; }
    public String getRefusedCallbackStatus() { return refusedCallbackStatus; }
    public void setRefusedCallbackStatus(String refusedCallbackStatus) { this.refusedCallbackStatus = refusedCallbackStatus; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public LocalDateTime getPreparedAt() { return preparedAt; }
    public void setPreparedAt(LocalDateTime preparedAt) { this.preparedAt = preparedAt; }
    public String getDispatchPayload() { return dispatchPayload; }
    public void setDispatchPayload(String dispatchPayload) { this.dispatchPayload = dispatchPayload; }
}
