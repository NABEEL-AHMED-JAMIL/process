package process.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.customer.CustomerEventJournal;
import process.customer.CustomerEventRelay;
import process.customer.CustomerEventRelayAccess;
import process.customer.CustomerRunStore;
import process.customer.CustomerRuns;
import process.customer.RunFiles;
import process.forms.JdbcFormStore;
import process.model.dto.ResponseDto;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.SourceJobService;
import process.model.service.impl.TransactionServiceImpl;
import process.outbox.OutboxWriter;
import process.pipeline.JdbcStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.review.JdbcRunReviewStore;
import process.pipeline.review.RunReviewEvents;
import process.pipeline.review.RunReviewService;
import process.pipeline.review.RunReviewStatus;
import process.pipeline.review.RunReviewStore;
import process.pipeline.review.RunReviewTaskListener;
import process.pipeline.review.RunReviews;
import process.storage.remote.StorageServiceClient;
import process.util.UserNameResolver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_PIPELINE;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_PIPELINE;
import static process.tenancy.CoreProbeFixture.B_RUN;

/**
 * MIG-361, Core's half, against a real etl_job as process_app (CoreProbeFixture): a run that completes waiting for its
 * internal review is announced on platform.process.run-review.v1 for the reviewers its pipeline names (a group here; the
 * administrators when it names none), in the run's own workspace and with none of its storage; the Task inbox's decision
 * comes back as workflow-service's instance-changed event and is recorded as that reviewer's internal decision, once; a
 * decision recorded any way is announced so an open task is closed -- but a customer's approval alone leaves the internal
 * task open. Opt-in like every ScratchPostgres test.
 */
class CoreRunReviewTasksPostgresTest {

    private static final String INTERNAL_BY_GROUP = "{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"keep\",\"name\":"
        + "\"Keep the notes\",\"task\":\"save_file\",\"config\":{\"fileName\":\"notes.csv\"}}],\"settings\":{\"review\":{\"required\":"
        + "[\"internal\"],\"reviewers\":{\"kind\":\"group\",\"value\":\"1063\"}}}}";
    private static final String BOTH_BY_DEFAULT = "{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"keep\",\"name\":"
        + "\"Keep the notes\",\"task\":\"save_file\",\"config\":{\"fileName\":\"notes.csv\"}}],\"settings\":{\"review\":{\"required\":"
        + "[\"internal\",\"customer\"]}}}";
    private static final long REVIEWER = 4641L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CoreProbeFixture fx;
    private static CustomerEventRelay relay;
    private static RunReviewService reviews;
    private static JdbcRunReviewStore reviewRows;
    private static CustomerEventJournal journal;
    private static RunReviewTaskListener listener;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_run_review_tasks");
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition, date_created) "
            + "SELECT tenant_id, pipeline_key, 1, ?::json, now() - interval '30 days' FROM pipeline WHERE pipeline_key = ?", INTERNAL_BY_GROUP,
            A_PIPELINE);
        sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition, date_created) "
            + "SELECT tenant_id, pipeline_key, 1, ?::json, now() - interval '30 days' FROM pipeline WHERE pipeline_key = ?", BOTH_BY_DEFAULT,
            B_PIPELINE);
        JdbcTemplate app = fx.db.appJdbc();
        JobQueueRepository runRows = fx.jpa.repository(JobQueueRepository.class);
        SourceJobRepository jobRows = fx.jpa.repository(SourceJobRepository.class);
        TransactionServiceImpl transactions = new TransactionServiceImpl(jobRows, fx.jpa.repository(SchedulerRepository.class), runRows,
            fx.jpa.repository(TaskReferenceRepository.class), fx.jpa.repository(JobAuditLogRepository.class),
            fx.jpa.repository(SourceTaskRepository.class), fx.openSearch);
        reviewRows = new JdbcRunReviewStore(app);
        RunReviews runReviews = new RunReviews(new JdbcStepStore(app), new PipelineDefinitionStore(app), reviewRows);
        reviews = new RunReviewService(runRows, jobRows, runReviews, reviewRows, mock(SourceJobService.class), transactions);
        journal = new CustomerEventJournal(app);
        reviews.setEventJournal(journal);
        CustomerRuns runs = new CustomerRuns(new CustomerRunStore(app), runRows, jobRows, runReviews, new JdbcStepStore(app),
            new PipelineDefinitionStore(app), new RunFiles(mock(StorageServiceClient.class)));
        relay = CustomerEventRelayAccess.relay(app, new TransactionTemplate(new DataSourceTransactionManager(fx.db.appPool())),
            new OutboxWriter(app), runs, runReviews, new JdbcStepStore(app), new JdbcFormStore(app));
        relay.setReviewEvents(new RunReviewEvents(new OutboxWriter(app), runReviews));
        UserNameResolver names = mock(UserNameResolver.class);
        when(names.namesFor(any())).thenReturn(Collections.singletonMap(REVIEWER, "Riverside Reviewer"));
        listener = new RunReviewTaskListener(reviews, names, fx.jpa.transactionManager());
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void startClean() {
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("UPDATE job_queue SET job_status = 'Queue', end_time = NULL WHERE job_queue_id IN (?, ?)", A_RUN, B_RUN);
        sql.update("DELETE FROM run_review_decision WHERE job_queue_id IN (?, ?)", A_RUN, B_RUN);
        sql.update("DELETE FROM run_review WHERE job_queue_id IN (?, ?)", A_RUN, B_RUN);
        sql.update("DELETE FROM api_event_out");
        sql.update("DELETE FROM platform_outbox");
    }

    @Test
    void aRunWaitingForItsInternalReviewIsAnnouncedForThePipelinesReviewers() throws Exception {
        complete(A_RUN);
        complete(B_RUN);

        relay.relay();

        List<JsonNode> events = this.published();
        assertThat(events).extracting(e -> e.path("eventType").asText()).containsOnly(RunReviewEvents.REQUESTED).hasSize(2);
        JsonNode a = events.stream().filter(e -> e.path("tenantId").asLong() == A).findFirst().get();
        JsonNode payload = a.path("payload");
        assertThat(payload.path("jobQueueId").asLong()).isEqualTo(A_RUN);
        assertThat(payload.path("jobId").asLong()).isEqualTo(A_JOB);
        assertThat(payload.path("reviewers").path("kind").asText()).isEqualTo("group");
        assertThat(payload.path("reviewers").path("value").asText()).isEqualTo("1063");
        assertThat(payload.path("title").asText()).startsWith("Review run #" + A_RUN);
        assertThat(payload.path("link").asText()).isEqualTo("/pipelines/schedules/" + A_JOB + "/runs/" + A_RUN + "/logs");
        assertThat(payload.path("finishedAt").asText()).endsWith("Z");
        assertThat(payload.toString()).doesNotContain("bucket").doesNotContain("Key\"").doesNotContain(String.valueOf(B_RUN));
        JsonNode b = events.stream().filter(e -> e.path("tenantId").asLong() == B).findFirst().get();
        assertThat(b.path("payload").path("reviewers").toString()).as("no reviewers named: the administrators")
            .isEqualTo("{\"kind\":\"role\",\"value\":\"TENANT_ADMIN\"}");
        assertThat(fx.db.jdbc().queryForList("SELECT message_key FROM platform_outbox WHERE topic = ? ORDER BY outbox_id", String.class,
            RunReviewEvents.TOPIC)).as("keyed by the run").containsExactlyInAnyOrder(String.valueOf(A_RUN), String.valueOf(B_RUN));
        assertThat(relay.relay()).as("once").isZero();
        assertThat(this.published()).hasSize(2);
    }

    @Test
    void theTaskInboxsDecisionIsRecordedAsTheReviewersOnceAndAnnounced() throws Exception {
        complete(A_RUN);
        relay.relay();

        String rejected = change(A, A_RUN, "pipeline-run-review", "Rejected", REVIEWER, "The label is wrong.");
        listener.onChange(rejected);
        listener.onChange(rejected);

        JdbcTemplate sql = fx.db.jdbc();
        assertThat(sql.queryForObject("SELECT status FROM run_review WHERE job_queue_id = ?", String.class, A_RUN)).isEqualTo("REJECTED");
        List<Map<String, Object>> decided = sql.queryForList("SELECT party, decision, reviewer_user_id, reviewer_name, comment, reason "
            + "FROM run_review_decision WHERE job_queue_id = ?", A_RUN);
        assertThat(decided).as("a redelivered decision records nothing more").hasSize(1);
        assertThat(decided.get(0)).containsEntry("party", "INTERNAL").containsEntry("decision", "REJECTED")
            .containsEntry("reviewer_user_id", REVIEWER).containsEntry("reviewer_name", "Riverside Reviewer")
            .containsEntry("comment", "The label is wrong.").containsEntry("reason", "The label is wrong.");
        assertThat(sql.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ?", String.class, A_RUN))
            .anySatisfy(line -> assertThat(line).contains("Task inbox (request 77, task 88)").contains("Riverside Reviewer"));

        relay.relay();
        List<JsonNode> events = this.published();
        assertThat(events).extracting(e -> e.path("eventType").asText()).containsExactly(RunReviewEvents.REQUESTED, RunReviewEvents.DECIDED);
        assertThat(events.get(1).path("payload").path("reviewStatus").asText()).isEqualTo("REJECTED");
    }

    @Test
    void anotherWorkspacesRequestOrAnotherWorkflowOrAnOpenRequestDecidesNothing() throws Exception {
        complete(A_RUN);

        for (String change : new String[] {
            change(B, A_RUN, "pipeline-run-review", "Approved", REVIEWER, null),       // B naming A's run
            change(A, A_RUN, "expense-approval", "Approved", REVIEWER, null),          // a person's own workflow
            change(A, A_RUN, "pipeline-run-review", "Running", REVIEWER, null),        // not decided yet
            change(A, A_RUN, "pipeline-run-review", "Cancelled", REVIEWER, null),      // decided elsewhere
            "not json"}) {
            listener.onChange(change);
        }

        assertThat(fx.db.jdbc().queryForObject("SELECT count(*) FROM run_review_decision WHERE job_queue_id = ?", Long.class, A_RUN)).isZero();
        // A run that is not completed is not reviewed.
        fx.db.jdbc().update("UPDATE job_queue SET job_status = 'Running' WHERE job_queue_id = ?", A_RUN);
        ResponseDto refused = RowSecurity.forTenant(A, () -> fx.jpa.transactions().execute(status -> reviews.decideFromInbox(A, A_RUN,
            ReviewDecision.APPROVED, REVIEWER, null, null, 77, 88)));
        assertThat(refused.getStatus()).isEqualTo("ERROR");
    }

    @Test
    void aCustomersApprovalAloneLeavesTheInternalTaskOpen() throws Exception {
        complete(B_RUN);
        relay.relay();
        RowSecurity.forTenant(B, () -> {
            reviewRows.lockPending(B_RUN, EnumSet.of(ReviewParty.INTERNAL, ReviewParty.CUSTOMER));
            RunReviewStore.Decision customer = new RunReviewStore.Decision();
            customer.jobQueueId = B_RUN;
            customer.attempt = 1;
            customer.party = ReviewParty.CUSTOMER;
            customer.decision = ReviewDecision.APPROVED;
            customer.reviewerName = "client:cl_bravo";
            customer.decidedAt = Instant.now();
            reviewRows.record(customer);
            reviewRows.settle(B_RUN, RunReviewStatus.PENDING, null);
            journal.reviewDecided(B, B_RUN);
            return null;
        });

        relay.relay();

        assertThat(this.published()).extracting(e -> e.path("eventType").asText()).containsExactly(RunReviewEvents.REQUESTED);
    }

    private static void complete(long run) {
        fx.db.jdbc().update("UPDATE job_queue SET job_status = 'Completed', end_time = now() WHERE job_queue_id = ?", run);
    }

    /** workflow-service's instance-changed event for a request of {@code definitionKey} about the run. */
    private static String change(long tenant, long run, String definitionKey, String state, long by, String comment) throws Exception {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("taskId", 88);
        decision.put("state", state);
        decision.put("actedBy", by);
        decision.put("actedFor", null);
        decision.put("comment", comment);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", 77);
        payload.put("tenantId", tenant);
        payload.put("definitionKey", definitionKey);
        payload.put("subjectType", "pipeline_run");
        payload.put("subjectId", "pipeline-run:" + run);
        payload.put("state", state);
        if ("Approved".equals(state) || "Rejected".equals(state)) {
            payload.put("decision", decision);
        }
        return JSON.writeValueAsString(Collections.singletonMap("payload", payload));
    }

    private List<JsonNode> published() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String event : fx.db.jdbc().queryForList("SELECT event FROM platform_outbox WHERE topic = ? ORDER BY outbox_id", String.class,
            RunReviewEvents.TOPIC)) {
            events.add(JSON.readTree(event));
        }
        return events;
    }
}
