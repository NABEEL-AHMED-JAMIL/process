package process.pipeline.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.barco.platform.event.PlatformEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.outbox.OutboxWriter;
import process.pipeline.PipelineDefinition;
import process.util.BusinessTime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MIG-361: a run's internal review as events, for the Task inbox (workflow-service's RunReviewTasks). On {@value #TOPIC},
 * keyed by the run -- so a run's request is read before its decision:
 *
 * <ul>
 *   <li>{@value #REQUESTED}: the run completed and its internal review waits -- {jobQueueId, jobId, jobName, pipelineId,
 *       attempt, finishedAt, title, link, reviewers {kind, value}}: the run's facts for the task (never a bucket or a
 *       key) and whose inbox it goes to (the pipeline's settings.review.reviewers, else the administrators);</li>
 *   <li>{@value #DECIDED}: a party decided the review, however -- {jobQueueId, reviewStatus}: a task still open for it is
 *       no longer needed.</li>
 * </ul>
 *
 * Written by CustomerEventRelay from the api_event_out journal, in the transaction that stamps the journal row: once per
 * completion and per decision, whichever path completed the run, and only for what committed.
 */
@Component
public class RunReviewEvents {

    public static final String TOPIC = "platform.process.run-review.v1";
    public static final String REQUESTED = "run-review.requested";
    public static final String DECIDED = "run-review.decided";
    static final String PRODUCER = "process";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxWriter outbox;
    private final RunReviews reviews;

    public RunReviewEvents(OutboxWriter outbox, RunReviews reviews) {
        this.outbox = outbox;
        this.reviews = reviews;
    }

    /** The run waits for its internal review: a task for its reviewers. */
    public void requested(long tenantId, JobQueue run, SourceJob job, Instant occurredAt) {
        PipelineDefinition.Reviewers reviewers = this.reviews.reviewersOf(run, job);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobQueueId", run.getJobQueueId());
        payload.put("jobId", job.getJobId());
        payload.put("jobName", job.getJobName());
        payload.put("pipelineId", job.getTaskDetail() == null ? null : job.getTaskDetail().getPipelineId());
        payload.put("attempt", Math.max(1, run.getAttempt()));
        // An instant (UTC, ...Z): the run's end on the business clock, converted, never a naive wall-clock string.
        payload.put("finishedAt", run.getEndTime() == null ? null : BusinessTime.instantOf(run.getEndTime()).toString());
        String title = String.format("Review run #%d: %s", run.getJobQueueId(), job.getJobName() == null ? "job " + job.getJobId()
            : job.getJobName());
        payload.put("title", title.length() > 255 ? title.substring(0, 255) : title);
        payload.put("link", String.format("/pipelines/schedules/%d/runs/%d/logs", job.getJobId(), run.getJobQueueId()));
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("kind", reviewers.getKind());
        who.put("value", reviewers.getValue());
        payload.put("reviewers", who);
        this.write(REQUESTED, tenantId, run.getJobQueueId(), payload, occurredAt);
    }

    /** A party decided the run's review; its status now. */
    public void decided(long tenantId, long jobQueueId, String reviewStatus, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobQueueId", jobQueueId);
        payload.put("reviewStatus", reviewStatus);
        this.write(DECIDED, tenantId, jobQueueId, payload, occurredAt);
    }

    private void write(String type, long tenantId, long jobQueueId, Map<String, Object> payload, Instant occurredAt) {
        PlatformEvent<Object> event = PlatformEvent.at(occurredAt == null ? Instant.now() : occurredAt, type, tenantId, PRODUCER, payload);
        event.setEventId(UUID.randomUUID().toString());
        try {
            this.outbox.write(TOPIC, String.valueOf(jobQueueId), event.getEventId(), JSON.writeValueAsString(event));
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A " + type + " event could not be written", unwritable);
        }
    }

    /** The topic, declared by its producer so it exists before workflow-service reads it: three partitions, keyed by run. */
    @Configuration
    public static class Topic {

        @Bean
        public NewTopic runReviewTopic(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
            return new NewTopic(TOPIC, 3, replication);
        }
    }
}
