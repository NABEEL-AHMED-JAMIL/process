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
import process.customer.CustomerEventTypes;
import process.customer.CustomerRunStore;
import process.customer.CustomerRuns;
import process.customer.RunFiles;
import process.forms.JdbcFormStore;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.outbox.OutboxWriter;
import process.pipeline.JdbcStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.review.JdbcRunReviewStore;
import process.pipeline.review.RunReviews;
import process.storage.remote.StorageServiceClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_PIPELINE;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_RUN;

/**
 * MIG-333: Core's events out, against a real etl_job as process_app (CoreProbeFixture). V203's triggers journal each
 * workspace's run statuses (Running, Completed, Failed; a heartbeat is not news), made files and settled form submissions
 * in the run's own workspace; the relay turns them into the customer's events on platform.customer.events.v1, each
 * carrying its own workspace and only that workspace's ids -- never a bucket or a storage key -- once, in order.
 * Opt-in like every ScratchPostgres test.
 */
class CoreCustomerEventsPostgresTest {

    static final String A_FILE = "01JACMEEVNT000000000000000";
    private static final String REVIEW = "{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"keep\",\"name\":\"Keep the "
        + "notes\",\"task\":\"save_file\",\"config\":{\"fileName\":\"notes.csv\"}}],\"settings\":{\"review\":{\"required\":[\"customer\"]}}}";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CoreProbeFixture fx;
    private static CustomerEventRelay relay;
    private static CustomerEventJournal journal;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_customer_events");
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition, date_created) "
            + "SELECT tenant_id, pipeline_key, 1, ?::json, now() - interval '30 days' FROM pipeline WHERE pipeline_key = ?", REVIEW, A_PIPELINE);
        JdbcTemplate app = fx.db.appJdbc();
        JobQueueRepository runRows = fx.jpa.repository(JobQueueRepository.class);
        SourceJobRepository jobRows = fx.jpa.repository(SourceJobRepository.class);
        RunReviews reviews = new RunReviews(new JdbcStepStore(app), new PipelineDefinitionStore(app), new JdbcRunReviewStore(app));
        CustomerRuns runs = new CustomerRuns(new CustomerRunStore(app), runRows, jobRows, reviews, new JdbcStepStore(app),
            new PipelineDefinitionStore(app), new RunFiles(mock(StorageServiceClient.class)));
        relay = CustomerEventRelayAccess.relay(app, new TransactionTemplate(new DataSourceTransactionManager(fx.db.appPool())),
            new OutboxWriter(app), runs, reviews, new JdbcStepStore(app), new JdbcFormStore(app));
        journal = new CustomerEventJournal(app);
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
        sql.update("DELETE FROM api_event_out");
        sql.update("DELETE FROM platform_outbox WHERE topic = ?", CustomerEventTypes.TOPIC);
    }

    @Test
    void eachRunsStatusesAreJournalledInItsOwnWorkspaceAndAHeartbeatIsNot() {
        JdbcTemplate sql = fx.db.jdbc();
        status(A_RUN, "Running");
        status(B_RUN, "Running");
        status(A_RUN, "Running"); // a heartbeat
        sql.update("UPDATE job_queue SET job_status = 'Start' WHERE job_queue_id = ?", A_RUN);                // not news
        sql.update("UPDATE job_queue SET job_status = 'Completed', end_time = now() WHERE job_queue_id = ?", A_RUN);
        sql.update("UPDATE job_queue SET job_status = 'Failed', end_time = now() WHERE job_queue_id = ?", B_RUN);

        assertThat(sql.queryForList("SELECT tenant_id || ' ' || job_queue_id || ' ' || job_status FROM api_event_out ORDER BY out_id", String.class))
            .containsExactly(A + " " + A_RUN + " Running", B + " " + B_RUN + " Running", A + " " + A_RUN + " Completed",
                B + " " + B_RUN + " Failed");
    }

    @Test
    void theRelaySendsEachWorkspaceOnlyItsOwnEventsOnceAndInOrder() throws Exception {
        JdbcTemplate sql = fx.db.jdbc();
        status(A_RUN, "Running");
        status(B_RUN, "Running");
        status(A_RUN, "Completed");
        status(B_RUN, "Completed");
        long step = sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status) "
            + "VALUES (?, 0, 'save_file', 'keep', 'Completed') RETURNING step_execution_id", Long.class, A_RUN);
        sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, byte_count, bucket_alias, object_key, file_id) "
            + "VALUES (?, 'bucket', 'notes.csv', 'csv', 1, 30, 'acme-secret-bucket', 'acme/secret/key.csv', ?)", step, A_FILE);

        assertThat(relay.relay()).isEqualTo(6);
        assertThat(relay.relay()).as("each journal row is sent once").isZero();

        List<JsonNode> events = this.published();
        List<String> seen = new ArrayList<>();
        for (JsonNode event : events) {
            long tenant = event.path("tenantId").asLong();
            JsonNode data = event.path("payload").path("data");
            String text = data.toString();
            seen.add(tenant + " " + event.path("eventType").asText());
            assertThat(event.path("payload").path("version").asInt()).isEqualTo(1);
            assertThat(text).doesNotContain("acme-secret-bucket").doesNotContain("acme/secret").doesNotContain("bucket");
            if (tenant == A) {
                assertThat(text).doesNotContain(String.valueOf(B_RUN));
            } else {
                assertThat(tenant).isEqualTo(B);
                assertThat(text).doesNotContain(String.valueOf(A_RUN)).doesNotContain(A_FILE);
            }
        }
        assertThat(seen).containsExactly(A + " run.started", B + " run.started", A + " run.completed", A + " run.review.requested",
            B + " run.completed", A + " file.available");

        JsonNode started = events.get(0).path("payload");
        assertThat(started.path("subject").asText()).isEqualTo("run:" + A_RUN);
        assertThat(started.path("data").path("id").asText()).isEqualTo(String.valueOf(A_RUN));
        assertThat(started.path("data").path("pipelineId").asText()).isEqualTo(String.valueOf(A_JOB));
        assertThat(started.path("data").path("status").asText()).as("as it stood: running, though completed since").isEqualTo("running");
        assertThat(started.path("data").path("endedAt").isNull()).isTrue();
        JsonNode completed = events.get(2).path("payload").path("data");
        assertThat(completed.path("status").asText()).isEqualTo("completed");
        assertThat(completed.path("endedAt").asText()).endsWith("Z");
        assertThat(completed.path("review").asText()).isEqualTo("pending");
        JsonNode requested = events.get(3).path("payload").path("data");
        assertThat(requested.path("review").path("required").toString()).isEqualTo("[\"customer\"]");
        JsonNode file = events.get(5).path("payload").path("data");
        assertThat(file.path("runId").asText()).isEqualTo(String.valueOf(A_RUN));
        assertThat(file.path("file").path("id").asText()).isEqualTo(A_FILE);
        assertThat(events.get(5).path("occurredAt").asText()).endsWith("Z");

        assertThat(sql.queryForList("SELECT DISTINCT message_key FROM platform_outbox WHERE topic = ?", String.class, CustomerEventTypes.TOPIC))
            .as("keyed by workspace").containsExactlyInAnyOrder(String.valueOf(A), String.valueOf(B));
    }

    @Test
    void aDecisionIsAnnouncedWithTheReviewAndADeletedRunIsNot() throws Exception {
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("UPDATE job_queue SET job_status = 'Completed', end_time = now() WHERE job_queue_id = ?", A_RUN);
        RowSecurity.forTenant(A, () -> journal.reviewDecided(A, A_RUN));
        sql.update("UPDATE job_queue SET job_status = 'Failed', end_time = now() WHERE job_queue_id = ?", B_RUN);
        sql.update("UPDATE job_queue SET status = 'Delete' WHERE job_queue_id = ?", B_RUN);
        try {
            relay.relay();
            List<String> types = new ArrayList<>();
            for (JsonNode event : this.published()) {
                types.add(event.path("tenantId").asLong() + " " + event.path("eventType").asText());
            }
            assertThat(types).containsExactly(A + " run.completed", A + " run.review.requested", A + " run.review.decided");
            assertThat(sql.queryForObject("SELECT count(*) FROM api_event_out WHERE published_at IS NULL", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT event_count FROM api_event_out WHERE job_queue_id = ?", Integer.class, B_RUN)).isZero();
        } finally {
            sql.update("UPDATE job_queue SET status = 'Active' WHERE job_queue_id = ?", B_RUN);
        }
    }

    @Test
    void aFormSubmissionIsAnnouncedWhenItSettles() throws Exception {
        JdbcTemplate sql = fx.db.jdbc();
        long noRun = sql.queryForObject("INSERT INTO form_definition (tenant_id, name, status) VALUES (?, 'Acme feedback', 'Active') "
            + "RETURNING form_id", Long.class, A);
        long withRun = sql.queryForObject("INSERT INTO form_definition (tenant_id, name, status, job_id) VALUES (?, 'Acme intake', 'Active', ?) "
            + "RETURNING form_id", Long.class, A, A_JOB);
        long received = sql.queryForObject("INSERT INTO form_submission (form_id, tenant_id, form_version, answers, status) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'Received') RETURNING submission_id", Long.class, noRun, A);
        long started = sql.queryForObject("INSERT INTO form_submission (form_id, tenant_id, form_version, answers, status, job_id) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'Received', ?) RETURNING submission_id", Long.class, withRun, A, A_JOB);
        assertThat(sql.queryForObject("SELECT count(*) FROM api_event_out WHERE submission_id = ?", Long.class, started))
            .as("not until the run starts").isZero();
        sql.update("UPDATE form_submission SET status = 'RunStarted', job_queue_id = ? WHERE submission_id = ?", A_RUN, started);

        relay.relay();
        List<JsonNode> events = this.published();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).path("eventType").asText()).isEqualTo("form.submission.received");
        assertThat(events.get(0).path("payload").path("data").path("id").asText()).isEqualTo(String.valueOf(received));
        assertThat(events.get(0).path("payload").path("data").path("runId").isNull()).isTrue();
        assertThat(events.get(1).path("payload").path("data").path("runId").asText()).isEqualTo(String.valueOf(A_RUN));
        assertThat(events.get(1).path("payload").path("data").path("receivedAt").asText()).endsWith("Z");
        assertThat(events.get(1).path("tenantId").asLong()).isEqualTo(A);
    }

    /** One run's status, as one statement: the journal's order is the statements' order. */
    private static void status(long run, String status) {
        fx.db.jdbc().update("UPDATE job_queue SET job_status = ?, end_time = CASE WHEN ? IN ('Completed', 'Failed') THEN now() END "
            + "WHERE job_queue_id = ?", status, status, run);
    }

    private List<JsonNode> published() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (Map<String, Object> row : fx.db.jdbc().queryForList("SELECT event FROM platform_outbox WHERE topic = ? ORDER BY outbox_id",
            CustomerEventTypes.TOPIC)) {
            events.add(JSON.readTree((String) row.get("event")));
        }
        return events;
    }
}
